package edu.eci.arsw.eciracego.model;

public class Ubication {
    private String runnerId;
    private double latitude;
    private double longitude;
    private long timestamp = System.currentTimeMillis();
    private double speed = 0.0;
    private boolean suspicious = false;
    private String roomCode = "CAMPUS-ECI";
    private VehicleType vehicleType = VehicleType.RENAULT_4;


    public Ubication() {}

    public Ubication(String corredorId, double latitud, double longitud) {
        this.runnerId = corredorId;
        this.latitude = latitud;
        this.longitude = longitud;
        this.timestamp = System.currentTimeMillis();
        this.roomCode = "CAMPUS-ECI";
    }

    public String getRunnerId() {
        return runnerId;
    }
    public void setRunnerId(String corredorId) {
        this.runnerId = corredorId;
    }
    public double getLatitude() {
        return latitude;
    }
    public void setLatitude(double latitud) {
        this.latitude = latitud;
    }
    public double getLongitude() {
        return longitude;
    }
    public void setLongitude(double longitud) {
        this.longitude = longitud;
    }

    public long getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getSpeed() {
        return speed;
    }
    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public boolean isSuspicious() {
        return suspicious;
    }
    public void setSuspicious(boolean suspicious) {
        this.suspicious = suspicious;
    }

    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) {
        this.roomCode = (roomCode != null && !roomCode.trim().isEmpty()) ? roomCode.trim().toUpperCase() : "CAMPUS-ECI";
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }
    public void setVehicleType(VehicleType vehicleType) {
        if (vehicleType != null) {
            this.vehicleType = vehicleType;
        }
    }

}
