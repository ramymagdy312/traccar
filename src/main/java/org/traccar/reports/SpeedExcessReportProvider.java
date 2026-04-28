package org.traccar.reports;

import org.apache.poi.ss.util.WorkbookUtil;
import org.traccar.config.Config;
import org.traccar.config.Keys;
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

public class SpeedExcessReportProvider {

    /**
     * Hard upper-bound for the merge window (5 minutes). Any configured value above this is clamped down so that
     * unrelated speed-excess events can never collapse into a single record. Mirrors the spec's strict rule:
     * {@code gap >= 5 minutes => DO NOT merge}.
     */
    private static final long MAX_MERGE_GAP_MILLIS = 5 * 60 * 1000L;

    /**
     * Fallback merge window (5 minutes) used when no config value is supplied. Mirrors the business rule:
     * {@code next.startTime - current.endTime < 5 minutes => merge}.
     */
    private static final long DEFAULT_MERGE_GAP_MILLIS = 5 * 60 * 1000L;

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

        long mergeGapMillis = resolveMergeGapMillis();
        Collection<SpeedExcessReportItem> result = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            if (positions.isEmpty()) {
                continue;
            }
            result.addAll(buildDeviceExcesses(device, positions, speedLimit, mergeGapMillis));
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, double speedLimit) throws StorageException, IOException {

        reportUtils.checkPeriodLimit(from, to);

        long mergeGapMillis = resolveMergeGapMillis();
        ArrayList<DeviceReportSection> devicesExcess = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            List<SpeedExcessReportItem> deviceExcesses =
                    buildDeviceExcesses(device, positions, speedLimit, mergeGapMillis);

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
     * Builds the list of speed-excess events for a single device. First we slice the position stream into raw
     * over-speed segments (the original behavior) and then we collapse adjacent segments whose inter-segment gap is
     * below the merge threshold so a single continuous violation is no longer fragmented into multiple rows.
     */
    private List<SpeedExcessReportItem> buildDeviceExcesses(
            Device device, List<Position> positions, double speedLimit, long mergeGapMillis) throws StorageException {

        List<Position> excessSegment = new ArrayList<>();
        List<SpeedExcessReportItem> deviceExcesses = new ArrayList<>();

        for (Position position : positions) {
            if (position.getSpeed() >= speedLimit) {
                excessSegment.add(position);
            } else if (!excessSegment.isEmpty()) {
                deviceExcesses.add(createExcessReport(device, excessSegment, speedLimit));
                excessSegment.clear();
            }
        }
        if (!excessSegment.isEmpty()) {
            deviceExcesses.add(createExcessReport(device, excessSegment, speedLimit));
        }

        return mergeConsecutiveExcesses(deviceExcesses, mergeGapMillis);
    }

    private SpeedExcessReportItem createExcessReport(
            Device device, List<Position> excessSegments, double speedLimit) throws StorageException {

        Position start = excessSegments.get(0);
        Position end = excessSegments.get(excessSegments.size() - 1);

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
        item.setDistance(PositionUtil.calculateDistance(start, end, true));
        item.setMaxSpeed(excessSegments.stream().mapToDouble(Position::getSpeed).max().orElse(0));
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
     * Resolves the configured merge gap, applying defensive bounds so that misconfiguration cannot produce false
     * merges. Negative or zero values disable merging; values above {@link #MAX_MERGE_GAP_MILLIS} are clamped.
     */
    private long resolveMergeGapMillis() {
        Long configuredSeconds = config.getLong(Keys.REPORT_SPEED_EXCESS_MERGE_GAP);
        long millis = configuredSeconds != null ? configuredSeconds * 1000L : DEFAULT_MERGE_GAP_MILLIS;
        if (millis <= 0) {
            return 0;
        }
        return Math.min(millis, MAX_MERGE_GAP_MILLIS);
    }

    /**
     * Walks the per-device list of speed-excess records (already in chronological order) and folds adjacent records
     * into a single event whenever the time gap between them is shorter than {@code mergeGapMillis}. Records on
     * different devices are never merged. Out-of-order records (negative gap) are also left untouched as a defensive
     * measure against clock skew or unsorted input.
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
     * <p>The merge rules follow the report specification:
     * <ul>
     *     <li>Start* fields come from the first record.</li>
     *     <li>End* fields come from the last record.</li>
     *     <li>{@code duration} is the wall-clock span from first start to last end (continuous duration).</li>
     *     <li>{@code distance} is the sum of the merged distances.</li>
     *     <li>{@code maxSpeed} is the maximum across both records.</li>
     *     <li>{@code averageSpeed} is a duration-weighted mean (degenerates to the higher of the two when both
     *         input durations are zero).</li>
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
     * Computes a duration-weighted mean of two average speeds. Using the individual durations as weights (rather
     * than a simple arithmetic mean) prevents a long fast segment followed by a short slow one from being reported
     * as if both contributed equally.
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
}
