package org.traccar.reports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.SpeedExcessReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpeedExcessReportProviderTest {

    private static final long DEVICE_ID = 7L;
    private static final double LIMIT_KPH = 100.0;
    private static final double LIMIT_KNOTS = UnitsConverter.knotsFromKph(LIMIT_KPH);
    private static final Date FROM = new Date(0L);
    private static final Date TO = new Date(10_000_000_000L);

    private Storage storage;
    private Config config;
    private ReportUtils reportUtils;
    private Device device;
    private SpeedExcessReportProvider provider;

    @BeforeEach
    public void init() throws Exception {
        storage = mock(Storage.class);
        config = new Config();
        reportUtils = mock(ReportUtils.class);

        device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getName()).thenReturn("test");
        when(device.getGroupId()).thenReturn(0L);

        when(storage.getObjects(eq(Device.class), any())).thenReturn(List.of(device));

        provider = new SpeedExcessReportProvider(config, storage, reportUtils);
    }

    private static Date time(String value) throws ParseException {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat.parse(value);
    }

    private static Position position(
            long id, String time, double latitude, double longitude, double speedKph, double totalDistance)
            throws ParseException {
        Position position = new Position();
        position.setId(id);
        position.setDeviceId(DEVICE_ID);
        position.setTime(time(time));
        position.setValid(true);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setSpeed(UnitsConverter.knotsFromKph(speedKph));
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
        return position;
    }

    private void givenPositions(List<Position> positions) throws StorageException {
        when(storage.getObjectsStream(eq(Position.class), any())).thenReturn(positions.stream());
    }

    private Collection<SpeedExcessReportItem> runReport() throws StorageException {
        return provider.getObjects(0L, List.of(DEVICE_ID), List.of(), FROM, TO, LIMIT_KNOTS);
    }

    @Test
    public void shortSpikeFilteredByDuration() throws Exception {
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 120, 1000),
                position(3, "2025-01-01 10:00:33.000", 0.006, 0.006, 120, 1100),
                position(4, "2025-01-01 10:01:00.000", 0.010, 0.010, 95, 2000)));

        assertTrue(runReport().isEmpty(), "3-second spike must be filtered as noise");
    }

    @Test
    public void zeroDistanceSpikeFilteredByDistance() throws Exception {
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 110, 1000),
                position(2, "2025-01-01 10:00:30.000", 0.000, 0.000, 110, 1000),
                position(3, "2025-01-01 10:01:00.000", 0.000, 0.000, 110, 1000),
                position(4, "2025-01-01 10:02:00.000", 0.000, 0.000, 95, 1000)));

        assertTrue(runReport().isEmpty(),
                "stationary device reporting high speed must be filtered as GPS noise");
    }

    @Test
    public void sustainedOverspeedIsReported() throws Exception {
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 120, 1000),
                position(3, "2025-01-01 10:01:00.000", 0.010, 0.010, 120, 2000),
                position(4, "2025-01-01 10:01:30.000", 0.015, 0.015, 120, 3000),
                position(5, "2025-01-01 10:02:00.000", 0.020, 0.020, 95, 4000)));

        Collection<SpeedExcessReportItem> events = runReport();

        assertEquals(1, events.size());
        SpeedExcessReportItem item = events.iterator().next();
        assertEquals(time("2025-01-01 10:00:30.000"), item.getStartTime());
        assertEquals(time("2025-01-01 10:01:30.000"), item.getEndTime());
        assertEquals(60_000L, item.getDuration());
        assertEquals(2000.0, item.getDistance(), 1e-6);
    }

    @Test
    public void mergesNearbyEventsIntoOne() throws Exception {
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 120, 1000),
                position(3, "2025-01-01 10:01:00.000", 0.010, 0.010, 120, 2000),
                position(4, "2025-01-01 10:01:30.000", 0.015, 0.015, 120, 3000),
                position(5, "2025-01-01 10:02:00.000", 0.020, 0.020, 95, 4000),
                position(6, "2025-01-01 10:02:30.000", 0.025, 0.025, 120, 5000),
                position(7, "2025-01-01 10:03:00.000", 0.030, 0.030, 120, 6000),
                position(8, "2025-01-01 10:03:30.000", 0.035, 0.035, 120, 7000),
                position(9, "2025-01-01 10:04:00.000", 0.040, 0.040, 95, 8000)));

        Collection<SpeedExcessReportItem> events = runReport();

        assertEquals(1, events.size(), "two close events must be merged into one continuous violation");
        SpeedExcessReportItem item = events.iterator().next();
        assertEquals(time("2025-01-01 10:00:30.000"), item.getStartTime());
        assertEquals(time("2025-01-01 10:03:30.000"), item.getEndTime());
        assertEquals(2000.0 + 2000.0, item.getDistance(), 1e-6);
    }

    @Test
    public void invalidGpsFixIsIgnored() throws Exception {
        Position invalid = position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 130, 1000);
        invalid.setValid(false);

        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                invalid,
                position(3, "2025-01-01 10:01:00.000", 0.010, 0.010, 95, 2000)));

        assertTrue(runReport().isEmpty(),
                "invalid GPS fixes must not produce Speed Excess events");
    }

    @Test
    public void teleportJumpIsIgnored() throws Exception {
        // Position 2 is several hundred kilometers away from position 1 within one second — a clear teleport.
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                position(2, "2025-01-01 10:00:01.000", 5.000, 5.000, 130, 1000),
                position(3, "2025-01-01 10:00:30.000", 0.005, 0.005, 95, 1500)));

        assertTrue(runReport().isEmpty(),
                "teleport positions implying impossible ground speeds must be discarded");
    }

    @Test
    public void lowAccuracyFixIsIgnored() throws Exception {
        Position bad = position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 130, 1000);
        bad.setAccuracy(500.0);

        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                bad,
                position(3, "2025-01-01 10:01:00.000", 0.010, 0.010, 95, 2000)));

        assertTrue(runReport().isEmpty(),
                "positions with poor GPS accuracy must not contribute to Speed Excess events");
    }

    @Test
    public void duplicateTimestampIsIgnored() throws Exception {
        // The duplicate (#3) reuses the same timestamp as #2 — the older copy stays, the new one is dropped.
        givenPositions(List.of(
                position(1, "2025-01-01 10:00:00.000", 0.000, 0.000, 95, 0),
                position(2, "2025-01-01 10:00:30.000", 0.005, 0.005, 120, 1000),
                position(3, "2025-01-01 10:00:30.000", 0.005, 0.005, 130, 1500),
                position(4, "2025-01-01 10:01:00.000", 0.010, 0.010, 120, 2000),
                position(5, "2025-01-01 10:01:30.000", 0.015, 0.015, 120, 3000),
                position(6, "2025-01-01 10:02:00.000", 0.020, 0.020, 95, 4000)));

        Collection<SpeedExcessReportItem> events = runReport();

        assertEquals(1, events.size());
        SpeedExcessReportItem item = events.iterator().next();
        // maxSpeed must reflect 120 km/h (the duplicate at 130 km/h was rejected).
        assertEquals(UnitsConverter.knotsFromKph(120), item.getMaxSpeed(), 1e-6);
    }
}
