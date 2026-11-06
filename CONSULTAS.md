# Hoja de consultas PromQL — ECIRace GO

Misma hoja del laboratorio, traducida a las métricas que expone ECIRace (Spring Boot Actuator + Micrometer).
El tablero "ECIRace GO Observabilidad" ya trae estos ocho paneles; esta hoja explica cada uno.

| Laboratorio (API Python) | ECIRace GO (Spring Boot) |
|---|---|
| `up{job="app"}` | `up{job="ecirace"}` |
| `lab_requests_total{status}` | `http_server_requests_seconds_count{status}` |
| `lab_request_duration_seconds_bucket` | `http_server_requests_seconds_bucket` (mismos límites: 5 ms … 5 s) |
| `lab_active_requests / lab_capacity` | `ecirace_lab_active_requests / ecirace_lab_capacity` |
| `lab_process_cpu_seconds_total` | `process_cpu_usage * system_cpu_count` |
| `lab_probe_*` (sonda) | `lab_probe_*` (la misma sonda, consultando `/lab/ready`) |

Las consultas excluyen `/actuator/*` para no contar las lecturas de Prometheus como tráfico.

## 1 Lectura del servicio
Stat; 0 rojo, 1 verde
```promql
up{job="ecirace"}
```

## 2 Disponibilidad funcional actual
Stat; 0 rojo, 1 verde
```promql
lab_probe_success
```

## 3 Disponibilidad observada en 5 minutos
Porcentaje 0–100
```promql
100 * increase(lab_probe_successes_total[5m]) / increase(lab_probe_attempts_total[5m])
```

## 4 Errores HTTP
Porcentaje; línea de referencia 1
```promql
100 * sum(rate(http_server_requests_seconds_count{job="ecirace",uri!~"/actuator.*",status=~"5.."}[1m])) / sum(rate(http_server_requests_seconds_count{job="ecirace",uri!~"/actuator.*"}[1m]))
```

## 5 Throughput total
Solicitudes/s; incluye respuestas fallidas
```promql
sum(rate(http_server_requests_seconds_count{job="ecirace",uri!~"/actuator.*"}[1m]))
```

## 6 P95 del servidor
Segundos; referencia 0.5
```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="ecirace",uri!~"/actuator.*"}[1m])))
```

## 7 Ocupación de capacidad
Porcentaje 0–100; muestra cada 5 s. Sin datos mientras la capacidad sea 0 (modo real, sin límite de cupos).
```promql
100 * ecirace_lab_active_requests / (ecirace_lab_capacity > 0)
```

## 8 CPU del proceso
Porcentaje de un núcleo; puede superar 100
```promql
100 * process_cpu_usage{job="ecirace"} * system_cpu_count{job="ecirace"}
```

## Opcional: hilos de Tomcat
Ocupación real del servidor web, útil en modo `real` donde no hay cupos simulados.
```promql
100 * tomcat_threads_busy_threads{job="ecirace"} / tomcat_threads_config_max_threads{job="ecirace"}
```

## Opcional: consulta a la base de datos
Tiempo medio real de `findById` en el repositorio de perfiles, en segundos.
NO incluye la espera simulada de la dependencia. Si la métrica no aparece en tu versión, omite este panel.
```promql
sum(rate(spring_data_repository_invocations_seconds_sum{repository="UserProfileRepository",method="findById"}[1m])) / sum(rate(spring_data_repository_invocations_seconds_count{repository="UserProfileRepository",method="findById"}[1m]))
```

---

# Tablero "ECIRace GO Proyecto": todo el sistema

Además de los 8 paneles del laboratorio, el tablero **ECIRace GO Proyecto** mide todas las partes de ECIRace.
Sus pruebas se ejecutan con `python scripts/lab.py proyecto carga|estres|pico|duracion|all`, que quita la
simulación del laboratorio y recorre la API REST y carreras completas por WebSocket.

| Parte del proyecto | Consulta principal | Qué responde |
|---|---|---|
| API REST | `histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{job="ecirace",uri=~"/api/.*"}[1m])))` | ¿Qué endpoint es el más lento? |
| Sesiones WebSocket | `ecirace_ws_sessions` | ¿Cuántos jugadores y tableros hay conectados? |
| Telemetría | `sum(rate(ecirace_ws_inbound_total{type="message",destination="telemetry"}[1m]))` | ¿Cuántas posiciones GPS llegan por segundo? |
| Telemetría aceptada | `sum(rate(ecirace_ws_broadcast_total{destination="room_telemetry"}[1m]))` | ¿Cuántas pasan el anti-cheat y se retransmiten? |
| Difusión | `sum by (destination) (rate(ecirace_ws_delivered_total[1m]))` | ¿Cuántos mensajes salen hacia los clientes? |
| Game loop | `histogram_quantile(0.95, sum by (le) (rate(tasks_scheduled_execution_seconds_bucket{job="ecirace"}[1m])))` | ¿El tick de 100 ms alcanza? |
| Salas | `sum by (status) (ecirace_rooms)` | ¿Cuántas salas hay esperando, en carrera y terminadas? |
| Corredores | `ecirace_runners_tracked` | ¿Cuántas posiciones recorre el game loop? |
| Pool RaceWorker | `ecirace_executor_queue_size` | ¿Se está acumulando telemetría sin procesar? |
| Base de datos | `hikaricp_connections_pending` | ¿Hay solicitudes esperando conexión? |
| k6 (cliente) | `max(k6_ecirace_e2e_latency_ms_p95{testid=~"$testid"})` | ¿Cuánto tarda una posición en ir y volver? (ms) |
| k6 (cliente) | `100 * max(k6_ecirace_race_finished_rate{testid=~"$testid"})` | ¿En qué % de carreras se detectó la meta? |

Notas:
- El game loop es la única tarea `@Scheduled` del proyecto, por eso `tasks_scheduled_execution_seconds` no necesita filtro.
- ECIRace no borra salas: `ecirace_rooms` crece con cada prueba. Para comparar dos ejecuciones en igualdad de
  condiciones, reinicie el servidor antes de cada una: `docker compose restart ecirace-server`.
- Los resultados finales de `proyecto` también aparecen en el tablero del laboratorio (*k6 Resultados por ejecucion*)
  con el perfil `proyecto-carga`, `proyecto-estres`, etc.
