package edu.eci.arsw.eciracego.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validación centralizada de todo lo que llega del cliente (nombres, códigos de sala, coordenadas).
 * Usa listas blancas: lo que no encaja se rechaza en vez de "limpiarse".
 */
public final class InputValidator {

    /** Tag del piloto: 3 a 16 caracteres, letras (con tildes), números, punto, guion y guion bajo. Sin espacios. */
    public static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_.\\-]{3,16}$");
    /** Código de sala: 3 a 20 caracteres en mayúsculas, números, guion y guion bajo. */
    public static final Pattern ROOM_CODE = Pattern.compile("^[A-Z0-9_\\-]{3,20}$");

    private InputValidator() {
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public static String requireUsername(String username) {
        String clean = username == null ? null : username.trim();
        if (!isValidUsername(clean)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_USERNAME",
                    "El tag debe tener entre 3 y 16 caracteres: letras, números, punto, guion o guion bajo (sin espacios).");
        }
        return clean;
    }

    /** Normaliza (trim + mayúsculas) y valida un código de sala; devuelve null si no es válido. */
    public static String normalizeRoomCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return ROOM_CODE.matcher(normalized).matches() ? normalized : null;
    }

    public static String requireRoomCode(String code) {
        String normalized = normalizeRoomCode(code);
        if (normalized == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ROOM",
                    "Código de sala inválido: 3 a 20 caracteres (letras, números, guion o guion bajo).");
        }
        return normalized;
    }

    public static boolean isValidLatitude(double lat) {
        return Double.isFinite(lat) && lat >= -90.0 && lat <= 90.0;
    }

    public static boolean isValidLongitude(double lon) {
        return Double.isFinite(lon) && lon >= -180.0 && lon <= 180.0;
    }

    public static boolean isValidCoordinate(double lat, double lon) {
        return isValidLatitude(lat) && isValidLongitude(lon);
    }

    /** Lee una coordenada de un cuerpo JSON (número o texto) y la valida. */
    public static double requireCoordinate(Object raw, boolean latitude) {
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                value = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw badCoordinate();
            }
        } else {
            throw badCoordinate();
        }
        boolean ok = latitude ? isValidLatitude(value) : isValidLongitude(value);
        if (!ok) {
            throw badCoordinate();
        }
        return value;
    }

    private static ApiException badCoordinate() {
        return ApiException.badRequest("Coordenadas de la meta inválidas.");
    }
}
