package edu.eci.arsw.eciracego.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Gestor de Conexiones Concurrentes:
 * Configura el ThreadPoolExecutor y el pool de Hilos de Trabajo (RaceWorker-X)
 * según el diagrama de arquitectura oficial para evitar cuellos de botella.
 */
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    @Bean(name = "raceTaskExecutor")
    public Executor raceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("RaceWorker-");
        // Si la cola se llena se descarta el paquete MÁS VIEJO (una posición GPS vieja ya no sirve).
        // Antes CallerRunsPolicy ejecutaba el trabajo en el hilo de entrada del WebSocket y un flood
        // de telemetría bloqueaba a todos los clientes.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}