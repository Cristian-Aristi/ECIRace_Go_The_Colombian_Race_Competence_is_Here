package edu.eci.arsw.eciracego.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public class Ubication {
    private String runnerId;
    private double latitude;
    private double longitude;
    private long timestamp = System.currentTimeMillis();
    private double speed = 0.0;
    private boolean suspicious = false;
    private String roomCode = "CAMPUS-ECI";
    private VehicleType vehicleType = VehicleType.RENAULT_4;
    private String car;


    public Ubication() {}

    /**
     * Solo para uso en código/tests. Se desactiva como creador de Jackson: con Spring Boot 4 (Jackson 3)
     * los nombres de parámetro del constructor se usan como nombres JSON, y antes exigía los campos
     * "latitud"/"longitud" (que el cliente nunca envía) -> "Cannot map null into type double" y se
     * perdía TODA la telemetría. Los parámetros se llaman igual que las propiedades JSON por seguridad.
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public Ubication(String runnerId, double latitude, double longitude) {
        this.runnerId = runnerId;
        this.latitude = latitude;
        this.longitude = longitude;
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
    public String getCar() {
        if (car != null && !car.trim().isEmpty()) {
            return car;
        }
        return vehicleType != null ? vehicleType.name() : "RENAULT_4";
    }

    public void setCar(String car) {
        this.car = car;
        if (car != null && !car.trim().isEmpty()) {
            try {
                this.vehicleType = VehicleType.valueOf(car.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

}
