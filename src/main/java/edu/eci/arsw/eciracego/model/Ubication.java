package edu.eci.arsw.eciracego.model;

public class Ubication {
    private String runnerId;
    private double latitude;
    private double longitude;
    private long timestamp = System.currentTimeMillis();
    private double speed = 0.0;
    private boolean suspicious = false;

    // Constructor vacío obligatorio para que Spring serialice el JSON
    public Ubication() {}

    public Ubication(String corredorId, double latitud, double longitud) {
        this.runnerId = corredorId;
        this.latitude = latitud;
        this.longitude = longitud;
        this.timestamp = System.currentTimeMillis();
    }

    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String corredorId) { this.runnerId = corredorId; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitud) { this.latitude = latitud; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitud) { this.longitude = longitud; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public boolean isSuspicious() { return suspicious; }
    public void setSuspicious(boolean suspicious) { this.suspicious = suspicious; }
}
