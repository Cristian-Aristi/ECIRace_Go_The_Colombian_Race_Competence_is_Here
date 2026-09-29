package edu.eci.arsw.eciracego.model;


public enum VehicleType {
    RENAULT_4("Renault 4 (Amigo Fiel)", 28.0, "#E8A33D"),
    CHEVROLET_SPARK("Chevrolet Spark GT", 30.0, "#3FA796"),
    MAZDA_323("Mazda 323 Coupé", 32.0, "#D6584B");

    private final String displayName;
    private final double maxSpeedKmH;
    private final String colorHex;

    VehicleType(String displayName, double maxSpeedKmH, String colorHex) {
        this.displayName = displayName;
        this.maxSpeedKmH = maxSpeedKmH;
        this.colorHex = colorHex;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getMaxSpeedKmH() {
        return maxSpeedKmH;
    }

    public String getColorHex() {
        return colorHex;
    }
}
