package edu.eci.arsw.eciracego.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Ruta de la petición tal como la enruta Spring: decodificada (%67 -> g), sin parámetros ";x" y sin
 * barras dobles. Los filtros de seguridad deben decidir con ESTA ruta y no con getRequestURI() (que
 * llega cruda): antes "POST /api/auth/%67uest" llegaba al mismo endpoint que /api/auth/guest pero
 * esquivaba el límite estricto de registros.
 */
public final class RequestPaths {

    private static final UrlPathHelper HELPER = new UrlPathHelper();

    static {
        HELPER.setAlwaysUseFullPath(true);
        HELPER.setUrlDecode(true);
        HELPER.setRemoveSemicolonContent(true);
    }

    private RequestPaths() {
    }

    public static String of(HttpServletRequest request) {
        try {
            String path = HELPER.getLookupPathForRequest(request);
            return path != null ? path : "";
        } catch (IllegalArgumentException e) {
            return request.getRequestURI() != null ? request.getRequestURI() : "";
        }
    }

    public static boolean isApi(HttpServletRequest request) {
        return of(request).startsWith("/api/");
    }
}
