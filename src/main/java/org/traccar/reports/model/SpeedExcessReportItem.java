package org.traccar.reports.model;

public class SpeedExcessReportItem extends TripReportItem {

    private double speedLimit;

    public double getSpeedLimit() {
        return speedLimit;
    }

    public void setSpeedLimit(double speedLimit) {
        this.speedLimit = speedLimit;
    }
}
