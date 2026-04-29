package org.traccar.reports;

import org.apache.poi.ss.util.WorkbookUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportSection;
import org.traccar.reports.model.SpeedExcessReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Builds Speed Excess reports with a focus on operational fairness rather than raw threshold crossings.
 *
 * <p>Each device's positions go through the following pipeline before any row is emitted:
 * <ol>
 *     <li>GPS quality filter — invalid fixes, outdated records, low-accuracy points, duplicate / out-of-order
 *         timestamps and any position whose implied ground speed (delta-distance / delta-time) exceeds a
 *         configurable plausibility ceiling are discarded. This neutralises teleport jumps, zero-distance
 *         high-speed spikes and noisy fixes that previously created false violations.</li>
 *     <li>Detection — consecutive trustworthy positions whose reported speed is at or above the configured
 *         speed limit are grouped into raw over-speed segments.</li>
 *     <li>Merge — adjacent segments whose inter-event gap is shorter than the configured threshold are folded
 *         into a single continuous event so a single real violation is no longer fragmented into many tiny
 *         rows.</li>
 *     <li>Threshold filter — events whose duration is below {@link Keys#REPORT_SPEED_EXCESS_MIN_DURATION} OR
 *         whose covered distance is below {@link Keys#REPORT_SPEED_EXCESS_MIN_DISTANCE} are dropped. Both
 *         checks are applied AFTER merging so legitimate sustained violations cannot be accidentally filtered
 *         out by fragmentation.</li>
 * </ol>
 *
 * <p>All thresholds are admin-configurable via the {@code report.speedExcess.*} keys.
 */
public class SpeedExcessReportProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedExcessReportProvider.class);

    /**
     * Hard upper-bound for the merge window (2 minutes). Any configured value above this is clamped down so
     * that unrelated speed-excess events can never collapse into a single record. Mirrors the spec's strict
     * rule: {@code gap >= 2 minutes => DO NOT merge}.
     */
    private static final long MAX_MERGE_GAP_MILLIS = 2 * 60 * 1000L;

    private final Config config;
    private final Storage storage;
    private final ReportUtils reportUtils;

    @Inject
    public SpeedExcessReportProvider(Config config, Storage storage, ReportUtils reportUtils) {
        this.config = config;
        this.storage = storage;
        this.reportUtils = reportUtils;
    }

    public Collection<SpeedExcessReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, double speedLimit) throws StorageException {

        reportUtils.checkPeriodLimit(from, to);

        FairnessSettings settings = resolveSettings();
        Collection<SpeedExcessReportItem> result = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            if (positions.isEmpty()) {
                continue;
            }
            result.addAll(buildDeviceExcesses(device, positions, speedLimit, settings));
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, double speedLimit) throws StorageException, IOException {

        reportUtils.checkPeriodLimit(from, to);

        FairnessSettings settings = resolveSettings();
        ArrayList<DeviceReportSection> devicesExcess = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            List<SpeedExcessReportItem> deviceExcesses = positions.isEmpty()
                    ? Collections.emptyList()
                    : buildDeviceExcesses(device, positions, speedLimit, settings);

            DeviceReportSection deviceExcess = new DeviceReportSection();
            deviceExcess.setDeviceName(device.getName());
            sheetNames.add(WorkbookUtil.createSafeSheetName(deviceExcess.getDeviceName()));
            if (device.getGroupId() > 0) {
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                if (group != null) {
                    deviceExcess.setGroupName(group.getName());
                }
            }
            deviceExcess.setObjects(deviceExcesses);
            devicesExcess.add(deviceExcess);
        }

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "overSpeed.xlsx").toFile();
        if (!file.exists()) {
            file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "trips.xlsx").toFile();
        }

        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("devices", devicesExcess);
            context.putVar("sheetNames", sheetNames);
            context.putVar("from", from);
            context.putVar("to", to);
            context.putVar("speedLimit", speedLimit);
            reportUtils.processTemplateWithSheets(inputStream, outputStream, context);
        }
    }

    /**
     * Runs the fairness pipeline (GPS quality filter → detect → merge → threshold filter) for one device.
     */
    private List<SpeedExcessReportItem> buildDeviceExcesses(
            Device device, List<Position> positions, double speedLimit, FairnessSettings settings)
            throws StorageException {

        List<Position> trustworthy = filterGpsQuality(positions, settings);
        if (trustworthy.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Position>> rawSegments = detectExcessSegments(trustworthy, speedLimit);
        if (rawSegments.isEmpty()) {
            return Collections.emptyList();
        }

        List<SpeedExcessReportItem> rawItems = new ArrayList<>(rawSegments.size());
        for (List<Position> segment : rawSegments) {
            rawItems.add(createExcessReport(device, segment, speedLimit));
        }

        List<SpeedExcessReportItem> merged = mergeConsecutiveExcesses(rawItems, settings.mergeGapMillis());

        List<SpeedExcessReportItem> reported = new ArrayList<>(merged.size());
        for (SpeedExcessReportItem item : merged) {
            if (meetsThresholds(item, settings)) {
                reported.add(item);
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Speed Excess event for device {} discarded as noise (duration={}ms, distance={}m)",
                        device.getId(), item.getDuration(), item.getDistance());
            }
        }
        return reported;
    }

    /**
     * Applies the GPS quality filter to a chronological position stream. A position is kept only when:
     * <ul>
     *     <li>{@code valid == true} (i.e. the device reported a real GPS fix)</li>
     *     <li>{@code outdated == false}</li>
     *     <li>its reported accuracy is within {@code maxAccuracyMeters} (when configured)</li>
     *     <li>its fix time is strictly greater than the previously kept position's fix time (no duplicates,
     *         no out-of-order records)</li>
     *     <li>the implied ground speed relative to the previously kept position does not exceed
     *         {@code maxPlausibleKnots} (when configured) — this rejects teleport jumps</li>
     * </ul>
     */
    private static List<Position> filterGpsQuality(List<Position> positions, FairnessSettings settings) {
        List<Position> filtered = new ArrayList<>(positions.size());
        Position previous = null;
        for (Position position : positions) {
            if (position.getFixTime() == null) {
                continue;
            }
            if (!position.getValid()) {
                continue;
            }
            if (position.getOutdated()) {
                continue;
            }
            if (settings.maxAccuracyMeters() > 0
                    && position.getAccuracy() > 0
                    && position.getAccuracy() > settings.maxAccuracyMeters()) {
                continue;
            }
            if (previous != null) {
                long deltaMillis = position.getFixTime().getTime() - previous.getFixTime().getTime();
                if (deltaMillis <= 0) {
                    continue;
                }
                if (settings.maxPlausibleKnots() > 0) {
                    double deltaMeters = DistanceCalculator.distance(previous, position);
                    double impliedKnots = UnitsConverter.knotsFromMps(deltaMeters / (deltaMillis / 1000.0));
                    if (impliedKnots > settings.maxPlausibleKnots()) {
                        continue;
                    }
                }
            }
            filtered.add(position);
            previous = position;
        }
        return filtered;
    }

    /**
     * Groups consecutive trustworthy positions whose reported speed is at or above the speed limit into raw
     * over-speed segments.
     */
    private static List<List<Position>> detectExcessSegments(List<Position> positions, double speedLimit) {
        List<List<Position>> segments = new ArrayList<>();
        List<Position> current = new ArrayList<>();
        for (Position position : positions) {
            if (position.getSpeed() >= speedLimit) {
                current.add(position);
            } else if (!current.isEmpty()) {
                segments.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    private SpeedExcessReportItem createExcessReport(
            Device device, List<Position> excessSegment, double speedLimit) throws StorageException {

        Position start = excessSegment.get(0);
        Position end = excessSegment.get(excessSegment.size() - 1);

        SpeedExcessReportItem item = new SpeedExcessReportItem();
        item.setDeviceId(device.getId());
        item.setDeviceName(device.getName());
        item.setSpeedLimit(speedLimit);

        item.setStartPositionId(start.getId());
        item.setStartLat(start.getLatitude());
        item.setStartLon(start.getLongitude());
        item.setStartAddress(start.getAddress());
        item.setStartTime(start.getFixTime());

        item.setEndPositionId(end.getId());
        item.setEndLat(end.getLatitude());
        item.setEndLon(end.getLongitude());
        item.setEndAddress(end.getAddress());
        item.setEndTime(end.getFixTime());

        long duration = end.getFixTime().getTime() - start.getFixTime().getTime();
        item.setDuration(duration);
        item.setDistance(segmentDistance(excessSegment));
        item.setMaxSpeed(excessSegment.stream().mapToDouble(Position::getSpeed).max().orElse(0));
        if (duration > 0) {
            item.setAverageSpeed(UnitsConverter.knotsFromMps(item.getDistance() * 1000 / duration));
        }
        item.setSpentFuel(reportUtils.calculateFuel(start, end, device));

        String driverUniqueId = reportUtils.findDriver(start, end);
        item.setDriverUniqueId(driverUniqueId);
        item.setDriverName(reportUtils.findDriverName(driverUniqueId));

        return item;
    }

    /**
     * Computes the distance covered during a segment, preferring odometer / total-distance values reported by
     * the device but falling back to a Haversine sum of consecutive position deltas when those values are
     * missing or zero. The fallback prevents devices that do not report odometer from having every event
     * filtered out by the {@code minDistance} threshold.
     */
    private static double segmentDistance(List<Position> segment) {
        if (segment.isEmpty()) {
            return 0.0;
        }
        double odometerDistance = segment.size() >= 2
                ? PositionUtil.calculateDistance(segment.get(0), segment.get(segment.size() - 1), true)
                : 0.0;
        if (odometerDistance > 0) {
            return odometerDistance;
        }
        double haversine = 0.0;
        for (int i = 1; i < segment.size(); i++) {
            haversine += DistanceCalculator.distance(segment.get(i - 1), segment.get(i));
        }
        return haversine;
    }

    /**
     * Resolves the configured fairness knobs. All values are clamped to safe ranges so misconfiguration cannot
     * produce nonsensical output: the merge gap is bounded above by {@link #MAX_MERGE_GAP_MILLIS}; minimum
     * duration / distance / accuracy / plausibility values are clamped to be non-negative.
     */
    private FairnessSettings resolveSettings() {
        long mergeGapSeconds = config.getLong(Keys.REPORT_SPEED_EXCESS_MERGE_GAP);
        long mergeGapMillis = clampMergeGap(mergeGapSeconds * 1000L);

        long minDurationMillis = Math.max(
                config.getLong(Keys.REPORT_SPEED_EXCESS_MIN_DURATION) * 1000L, 0L);
        double minDistanceMeters = Math.max(config.getLong(Keys.REPORT_SPEED_EXCESS_MIN_DISTANCE), 0L);
        double maxAccuracy = Math.max(config.getDouble(Keys.REPORT_SPEED_EXCESS_MAX_ACCURACY), 0.0);
        double maxPlausibleKph = Math.max(config.getDouble(Keys.REPORT_SPEED_EXCESS_MAX_PLAUSIBLE_KPH), 0.0);
        double maxPlausibleKnots = maxPlausibleKph > 0 ? UnitsConverter.knotsFromKph(maxPlausibleKph) : 0.0;

        return new FairnessSettings(
                minDurationMillis, minDistanceMeters, mergeGapMillis, maxAccuracy, maxPlausibleKnots);
    }

    /**
     * Clamps the merge gap to non-negative values and to {@link #MAX_MERGE_GAP_MILLIS}. Returning 0 disables
     * merging entirely.
     */
    private static long clampMergeGap(long millis) {
        if (millis <= 0) {
            return 0;
        }
        return Math.min(millis, MAX_MERGE_GAP_MILLIS);
    }

    /**
     * Walks the per-device list of speed-excess records (already in chronological order) and folds adjacent
     * records into a single event whenever the time gap between them is shorter than {@code mergeGapMillis}.
     * Records on different devices are never merged. Out-of-order records (negative gap) are also left
     * untouched as a defensive measure against clock skew or unsorted input.
     */
    private List<SpeedExcessReportItem> mergeConsecutiveExcesses(
            List<SpeedExcessReportItem> items, long mergeGapMillis) {

        if (items.size() < 2 || mergeGapMillis <= 0) {
            return items;
        }

        List<SpeedExcessReportItem> merged = new ArrayList<>(items.size());
        SpeedExcessReportItem current = items.get(0);

        for (int i = 1; i < items.size(); i++) {
            SpeedExcessReportItem next = items.get(i);
            if (shouldMerge(current, next, mergeGapMillis)) {
                current = mergeItems(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static boolean shouldMerge(
            SpeedExcessReportItem current, SpeedExcessReportItem next, long mergeGapMillis) {

        if (current.getDeviceId() != next.getDeviceId()) {
            return false;
        }
        Date currentEnd = current.getEndTime();
        Date nextStart = next.getStartTime();
        if (currentEnd == null || nextStart == null) {
            return false;
        }
        long gap = nextStart.getTime() - currentEnd.getTime();
        return gap >= 0 && gap < mergeGapMillis;
    }

    /**
     * Combines two adjacent speed-excess records into a single continuous event.
     *
     * <p>Merge rules:
     * <ul>
     *     <li>Start* fields come from the first record.</li>
     *     <li>End* fields come from the last record.</li>
     *     <li>{@code duration} is the wall-clock span from first start to last end (continuous duration).</li>
     *     <li>{@code distance} is the sum of the merged distances.</li>
     *     <li>{@code maxSpeed} is the maximum across both records.</li>
     *     <li>{@code averageSpeed} is a duration-weighted mean (degenerates to the higher of the two when
     *         both input durations are zero).</li>
     *     <li>{@code spentFuel} is summed.</li>
     *     <li>{@code speedLimit} is preserved from the first record (identical for both by construction).</li>
     *     <li>Driver information is preserved only when both records reference the same driver.</li>
     * </ul>
     */
    private SpeedExcessReportItem mergeItems(SpeedExcessReportItem first, SpeedExcessReportItem second) {
        SpeedExcessReportItem merged = new SpeedExcessReportItem();

        merged.setDeviceId(first.getDeviceId());
        merged.setDeviceName(first.getDeviceName());
        merged.setSpeedLimit(first.getSpeedLimit());

        merged.setStartPositionId(first.getStartPositionId());
        merged.setStartLat(first.getStartLat());
        merged.setStartLon(first.getStartLon());
        merged.setStartAddress(first.getStartAddress());
        merged.setStartTime(first.getStartTime());

        merged.setEndPositionId(second.getEndPositionId());
        merged.setEndLat(second.getEndLat());
        merged.setEndLon(second.getEndLon());
        merged.setEndAddress(second.getEndAddress());
        merged.setEndTime(second.getEndTime());

        long continuousDuration = second.getEndTime().getTime() - first.getStartTime().getTime();
        merged.setDuration(Math.max(continuousDuration, 0));

        merged.setDistance(first.getDistance() + second.getDistance());
        merged.setMaxSpeed(Math.max(first.getMaxSpeed(), second.getMaxSpeed()));
        merged.setAverageSpeed(weightedAverageSpeed(first, second));
        merged.setSpentFuel(first.getSpentFuel() + second.getSpentFuel());

        if (first.getStartOdometer() > 0) {
            merged.setStartOdometer(first.getStartOdometer());
        }
        if (second.getEndOdometer() > 0) {
            merged.setEndOdometer(second.getEndOdometer());
        }

        if (Objects.equals(first.getDriverUniqueId(), second.getDriverUniqueId())) {
            merged.setDriverUniqueId(first.getDriverUniqueId());
            merged.setDriverName(first.getDriverName());
        }

        return merged;
    }

    /**
     * Computes a duration-weighted mean of two average speeds. Using the individual durations as weights
     * (rather than a simple arithmetic mean) prevents a long fast segment followed by a short slow one from
     * being reported as if both contributed equally.
     */
    private static double weightedAverageSpeed(SpeedExcessReportItem first, SpeedExcessReportItem second) {
        long durationFirst = Math.max(first.getDuration(), 0);
        long durationSecond = Math.max(second.getDuration(), 0);
        long total = durationFirst + durationSecond;
        if (total <= 0) {
            return Math.max(first.getAverageSpeed(), second.getAverageSpeed());
        }
        return (first.getAverageSpeed() * durationFirst + second.getAverageSpeed() * durationSecond) / total;
    }

    /**
     * Returns whether a (post-merge) event passes both the minimum-duration and minimum-distance thresholds.
     * Events failing either check are treated as GPS noise / spikes and discarded — this is the OR semantics
     * specified by the report requirements: a real overspeed event must be both long enough AND cover enough
     * distance to be considered fair.
     */
    private static boolean meetsThresholds(SpeedExcessReportItem item, FairnessSettings settings) {
        if (settings.minDurationMillis() > 0 && item.getDuration() < settings.minDurationMillis()) {
            return false;
        }
        if (settings.minDistanceMeters() > 0 && item.getDistance() < settings.minDistanceMeters()) {
            return false;
        }
        return true;
    }

    /**
     * Immutable bundle of resolved Speed Excess fairness settings. Speeds are stored in knots (the engine's
     * native unit) so they can be compared directly against {@link Position#getSpeed()} without per-record
     * unit conversion. A zero value for {@code maxAccuracyMeters} or {@code maxPlausibleKnots} disables the
     * corresponding GPS quality check.
     */
    private record FairnessSettings(
            long minDurationMillis,
            double minDistanceMeters,
            long mergeGapMillis,
            double maxAccuracyMeters,
            double maxPlausibleKnots) {
    }
}
