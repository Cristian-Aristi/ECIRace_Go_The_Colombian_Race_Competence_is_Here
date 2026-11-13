package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Ubication;
import org.springframework.stereotype.Service;

@Service
public class AntiCheatEngine {

    /**
     * Modalidades de carrera basadas en biometría de velocidad humana:
     * - WALKING: Caminata enérgica (máx 7 km/h)
     * - JOGGING: Trote continuo (máx 12 km/h)
     * - RUNNING: Carrera / Sprint (máx 30 km/h)
     */
    public enum RaceMode {
        WALKING(7.0),
        JOGGING(12.0),
        RUNNING(30.0);

        private final double maxSpeedKmH;

        RaceMode(double maxSpeedKmH) {
            this.maxSpeedKmH = maxSpeedKmH;
        }

        public double getMaxSpeedKmH() {
            return maxSpeedKmH;
        }
    }

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    // Por defecto en RUNNING (30 km/h) para Sprint 2
    private RaceMode currentMode = RaceMode.RUNNING;


    public double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    public double calculateSpeedKmH(Ubication previous, Ubication current) {
        if (previous == null || current == null) return 0.0;

        long deltaMillis = current.getTimestamp() - previous.getTimestamp();
        if (deltaMillis <= 0) deltaMillis = 1500;

        double distanceMeters = calculateDistanceMeters(
                previous.getLatitude(), previous.getLongitude(),
                current.getLatitude(), current.getLongitude());

        double deltaSeconds = deltaMillis / 1000.0;
        double speedMps = distanceMeters / deltaSeconds;
        return speedMps * 3.6; // m/s a km/h
    }

    /** Velocidad en km/h para una distancia en metros recorrida en un intervalo en milisegundos. */
    public double speedKmH(double distanceMeters, long deltaMillis) {
        if (!Double.isFinite(distanceMeters) || deltaMillis <= 0) {
            return Double.NaN;
        }
        return (distanceMeters / (deltaMillis / 1000.0)) * 3.6;
    }

    /**
     * Valida según la modalidad actual de la carrera.
     */
    public boolean isSuspicious(double speedKmH) {
        return isSuspicious(speedKmH, this.currentMode);
    }

    /**
     * Valida según una modalidad específica (Walking, Jogging o Running).
     */
    public boolean isSuspicious(double speedKmH, RaceMode mode) {
        // NaN o Infinity también son sospechosos (antes NaN > 30 daba false y se aceptaba)
        return !Double.isFinite(speedKmH) || speedKmH > mode.getMaxSpeedKmH();
    }

    public void setRaceMode(RaceMode mode) {
        this.currentMode = mode;
    }

    public RaceMode getRaceMode() {
        return currentMode;
    }
}