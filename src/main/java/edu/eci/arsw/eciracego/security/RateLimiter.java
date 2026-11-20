package edu.eci.arsw.eciracego.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador en memoria por clave (IP, sesión STOMP...) con cubetas de fichas.
 * Una sola instancia del servidor, así que no hace falta un almacén compartido.
 */
@Component
public class RateLimiter {

    /** Tope de claves en memoria: si alguien rota IPs para inflar el mapa, se vacía (falla abierto, no se cae). */
    private static final int MAX_KEYS = 50_000;
    private static final long IDLE_EVICT_MS = 10 * 60_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity      ráfaga máxima
     * @param refillPerSec  fichas que se recuperan por segundo
     * @return true si la acción se permite
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSec) {
        if (buckets.size() > MAX_KEYS) {
            buckets.clear();
        }
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        return bucket.tryTake(capacity, refillPerSec, now);
    }

    public void forget(String key) {
        buckets.remove(key);
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictIdle() {
        long limit = System.nanoTime() - IDLE_EVICT_MS * 1_000_000L;
        buckets.entrySet().removeIf(e -> e.getValue().lastNanos < limit);
    }

    int size() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private volatile long lastNanos;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastNanos = now;
        }

        synchronized boolean tryTake(int capacity, double refillPerSec, long now) {
            double elapsedSec = Math.max(0, now - lastNanos) / 1e9;
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
