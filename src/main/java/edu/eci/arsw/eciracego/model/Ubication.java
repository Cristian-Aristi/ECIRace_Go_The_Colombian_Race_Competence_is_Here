package edu.eci.arsw.eciracego.model;

public class Ubication {
    private String corredorId;
    private double latitud;
    private double longitud;

    // Constructor vacío obligatorio para que Spring serialice el JSON
    public Ubication() {}

    public Ubication(String corredorId, double latitud, double longitud) {
        this.corredorId = corredorId;
        this.latitud = latitud;
        this.longitud = longitud;
    }

    public String getCorredorId() { return corredorId; }
    public void setCorredorId(String corredorId) { this.corredorId = corredorId; }
    public double getLatitud() { return latitud; }
    public void setLatitud(double latitud) { this.latitud = latitud; }
    public double getLongitud() { return longitud; }
    public void setLongitud(double longitud) { this.longitud = longitud; }
}
