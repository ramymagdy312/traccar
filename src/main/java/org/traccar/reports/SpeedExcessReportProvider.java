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

public class SpeedExcessReportProvider {

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

        Collection<SpeedExcessReportItem> result = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);

            if (positions.isEmpty()) {
                continue;
            }

            List<Position> excessSegments = new ArrayList<>();
            List<SpeedExcessReportItem> deviceExcesses = new ArrayList<>();

            for (Position position : positions) {
                if (position.getSpeed() >= speedLimit) {
                    excessSegments.add(position);
                } else if (!excessSegments.isEmpty()) {
                    deviceExcesses.add(createExcessReport(device, excessSegments, speedLimit));
                    excessSegments.clear();
                }
            }

            if (!excessSegments.isEmpty()) {
                deviceExcesses.add(createExcessReport(device, excessSegments, speedLimit));
            }
            result.addAll(deviceExcesses);
        }
        return result;
    }

    private SpeedExcessReportItem createExcessReport(Device device, List<Position> excessSegments, double speedLimit) {
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

        return item;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, double speedLimit) throws StorageException, IOException {

        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesExcess = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();
        Collection<Long> filteredGroupIds = groupIds != null ? groupIds : Collections.emptyList();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, filteredGroupIds)) {
            List<Position> positions = PositionUtil.getPositions(storage, device.getId(), from, to);

            List<Position> excessSegments = new ArrayList<>();
            List<SpeedExcessReportItem> deviceExcesses = new ArrayList<>();

            for (Position position : positions) {
                if (position.getSpeed() >= speedLimit) {
                    excessSegments.add(position);
                } else if (!excessSegments.isEmpty()) {
                    deviceExcesses.add(createExcessReport(device, excessSegments, speedLimit));
                    excessSegments.clear();
                }
            }

            if (!excessSegments.isEmpty()) {
                deviceExcesses.add(createExcessReport(device, excessSegments, speedLimit));
            }

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
}
