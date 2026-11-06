package edu.eci.arsw.eciracego.observability;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.RoomService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Indicadores del estado del juego, leídos (solo lectura) de los servicios existentes en cada consulta
 * de Prometheus. No cambian ninguna lógica.
 *
 * En Prometheus:
 *   ecirace_rooms{status="waiting|in_progress|finished"}   salas por estado
 *   ecirace_runners_tracked                                corredores con posición en memoria (lo que recorre el game loop)
 *   ecirace_users_registered                               perfiles en la base de datos
 *   ecirace_executor_active | _pool_size | _pool_max | _queue_size | _completed_tasks_total   pool RaceWorker
 */
@Component
public class ProjectGauges {

    public ProjectGauges(MeterRegistry registry,
                         RoomService roomService,
                         RaceManager raceManager,
                         UserProfileRepository users,
                         @Qualifier("raceTaskExecutor") Executor raceTaskExecutor) {

        for (Room.RoomStatus status : Room.RoomStatus.values()) {
            Gauge.builder("ecirace.rooms", roomService,
                            s -> s.getAllRooms().stream().filter(r -> r.getStatus() == status).count())
                    .description("Salas actuales por estado")
                    .tag("status", status.name().toLowerCase())
                    .register(registry);
        }

        Gauge.builder("ecirace.runners.tracked", raceManager, m -> m.getGlobalState().size())
                .description("Corredores con posición en memoria (los que recorre el game loop cada 100 ms)")
                .register(registry);

        Gauge.builder("ecirace.users.registered", users, UserProfileRepository::count)
                .description("Perfiles de piloto guardados en la base de datos")
                .register(registry);

        if (raceTaskExecutor instanceof ThreadPoolTaskExecutor pool) {
            Gauge.builder("ecirace.executor.active", pool, p -> p.getThreadPoolExecutor().getActiveCount())
                    .description("Hilos RaceWorker procesando telemetría").register(registry);
            Gauge.builder("ecirace.executor.pool.size", pool, p -> p.getThreadPoolExecutor().getPoolSize())
                    .description("Hilos RaceWorker creados").register(registry);
            Gauge.builder("ecirace.executor.pool.max", pool, p -> p.getThreadPoolExecutor().getMaximumPoolSize())
                    .description("Máximo de hilos RaceWorker").register(registry);
            Gauge.builder("ecirace.executor.queue.size", pool, p -> p.getThreadPoolExecutor().getQueue().size())
                    .description("Paquetes de telemetría esperando hilo libre").register(registry);
            FunctionCounter.builder("ecirace.executor.completed.tasks", pool,
                            p -> {
                                ThreadPoolExecutor e = p.getThreadPoolExecutor();
                                return e.getCompletedTaskCount();
                            })
                    .description("Paquetes de telemetría procesados por el pool (acumulado)").register(registry);
        }
    }
}
