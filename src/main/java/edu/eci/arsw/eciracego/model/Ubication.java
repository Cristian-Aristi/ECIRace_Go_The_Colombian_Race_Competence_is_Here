package edu.eci.arsw.eciracego.model;

public class Ubication {
    private String runnerId;
    private double latitude;
    private double longitude;

    // Constructor vacío obligatorio para que Spring serialice el JSON
    public Ubication() {}

    public Ubication(String corredorId, double latitud, double longitud) {
        this.runnerId = corredorId;
        this.latitude = latitud;
        this.longitude = longitud;
    }

    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String corredorId) { this.runnerId = corredorId; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitud) { this.latitude = latitud; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitud) { this.longitude = longitud; }
}
