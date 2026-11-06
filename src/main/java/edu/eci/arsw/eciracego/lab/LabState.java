package edu.eci.arsw.eciracego.lab;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado del "modo laboratorio": equivalente al STATE y a los SLOTS de app/server.py del lab ARSW.
 *
 *  - ready:    si es false, la API (/api/**) y /actuator/health/readiness responden 503,
 *              pero /actuator/prometheus sigue respondiendo (contraste up vs. sonda).
 *  - delayMs:  espera simulada de una dependencia externa (NO es tiempo real de ECIRace).
 *  - capacity: cupos simultáneos; al agotarse la API rechaza con 503. 0 = sin límite.
 *
 * Solo existe si ecirace.lab.enabled=true (docker-compose lo activa). Al arrancar no simula nada
 * (delay 0, sin límite): "python scripts/lab.py reset" aplica la línea base del lab (120 ms, 8 cupos).
 */
@Component
@ConditionalOnProperty(name = "ecirace.lab.enabled", havingValue = "true")
public class LabState {

    public static final int MAX_DELAY_MS = 1000;
    public static final int MAX_CAPACITY = 1000;

    private final ApplicationEventPublisher publisher;
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean ready = true;
    private volatile int delayMs;
    private volatile int capacity;

    public LabState(ApplicationEventPublisher publisher,
                    MeterRegistry registry,
                    @Value("${ecirace.lab.delay-ms:0}") int delayMs,
                    @Value("${ecirace.lab.capacity:0}") int capacity) {
        validate(delayMs, capacity);
        this.publisher = publisher;
        this.delayMs = delayMs;
        this.capacity = capacity;

        // Equivalentes a lab_active_requests y lab_capacity del laboratorio
        Gauge.builder("ecirace.lab.active.requests", active, AtomicInteger::get)
                .description("Solicitudes /api ocupando un cupo en este instante")
                .register(registry);
        Gauge.builder("ecirace.lab.capacity", this, s -> s.capacity)
                .description("Cupos simultáneos del modo laboratorio (0 = sin límite)")
                .register(registry);
        Gauge.builder("ecirace.lab.delay.ms", this, s -> s.delayMs)
                .description("Espera simulada de la dependencia, en milisegundos")
                .register(registry);
        Gauge.builder("ecirace.lab.ready", this, s -> s.ready ? 1 : 0)
                .description("1 si la API acepta tráfico, 0 durante una falla inyectada")
                .register(registry);
    }

    /** Intenta ocupar un cupo sin bloquear (como SLOTS.acquire(blocking=False) del lab). */
    public boolean tryAcquire() {
        while (true) {
            int current = active.get();
            int cap = capacity;
            if (cap > 0 && current >= cap) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        active.decrementAndGet();
    }

    /**
     * Cambia la configuración. Los parámetros null no se modifican.
     *
     * @throws IllegalArgumentException si algún valor está fuera de rango (no se aplica nada)
     */
    public synchronized void update(Boolean newReady, Integer newDelayMs, Integer newCapacity) {
        int d = (newDelayMs != null) ? newDelayMs : delayMs;
        int c = (newCapacity != null) ? newCapacity : capacity;
        validate(d, c);

        delayMs = d;
        capacity = c;
        if (newReady != null && newReady != ready) {
            ready = newReady;
            // Hace que /actuator/health/readiness (lo que consulta la sonda) responda 200 o 503
            AvailabilityChangeEvent.publish(publisher, this,
                    newReady ? ReadinessState.ACCEPTING_TRAFFIC : ReadinessState.REFUSING_TRAFFIC);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ready", ready);
        map.put("delay_ms", delayMs);
        map.put("capacity", capacity);
        map.put("active", active.get());
        return map;
    }

    public boolean isReady() {
        return ready;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getActive() {
        return active.get();
    }

    private static void validate(int delayMs, int capacity) {
        if (delayMs < 0 || delayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException("delay_ms debe estar entre 0 y " + MAX_DELAY_MS);
        }
        if (capacity < 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity debe estar entre 0 y " + MAX_CAPACITY);
        }
    }
}
