const urlParams = new URLSearchParams(window.location.search);
let runnerId = urlParams.get('player');
let selectedCar = urlParams.get('car');

// 🚀 1. Blindaje de localStorage y lectura del carro elegido en el Lobby
try {
    if (!runnerId) {
        runnerId = localStorage.getItem('ecirace_username');
    }
    if (!selectedCar) {
        selectedCar = localStorage.getItem('ecirace_car') || localStorage.getItem('selectedCar');
    }
} catch (e) {
    console.warn("Memoria local restringida por el navegador.");
}

if (!runnerId || runnerId.trim() === '') {
    alert("Acceso denegado: Debes iniciar sesión en el Lobby antes de ingresar a la pista.");
    window.location.href = "index.html";
}
runnerId = runnerId.trim();
// Solo se aceptan los valores del enum VehicleType (los mismos que usa el Lobby y el Dashboard)
const VEHICULOS_VALIDOS = ['RENAULT_4', 'CHEVROLET_SPARK', 'MAZDA_323'];
selectedCar = (selectedCar || '').trim().toUpperCase();
if (!VEHICULOS_VALIDOS.includes(selectedCar)) selectedCar = 'RENAULT_4';

try {
    localStorage.setItem('ecirace_username', runnerId);
    localStorage.setItem('ecirace_car', selectedCar);
} catch (e) {}

const roomCode = (urlParams.get('room') || 'CAMPUS-ECI').toUpperCase();
document.getElementById('runnerIdDisplay').innerText = runnerId;
document.getElementById('roomDisplay').innerText = roomCode;
document.getElementById('gateRoomCode').innerText = roomCode;

const hudStatus = document.getElementById('hudStatus');
const sourceBadge = document.getElementById('sourceBadge');
const speedDisplay = document.getElementById('speedDisplay');
const latDisplay = document.getElementById('latDisplay');
const lngDisplay = document.getElementById('lngDisplay');
const accDisplay = document.getElementById('accDisplay');
const timeDisplay = document.getElementById('timeDisplay');
const eventLog = document.getElementById('eventLog');
const btnStartGps = document.getElementById('btnStartGps');
const btnStopGps = document.getElementById('btnStopGps');
const btnToggleSim = document.getElementById('btnToggleSim');
const raceGateCard = document.getElementById('raceGateCard');
const raceStateDisplay = document.getElementById('raceStateDisplay');
const wsStatusDisplay = document.getElementById('wsStatusDisplay');
const packetsCountDisplay = document.getElementById('packetsCountDisplay');

let watchId = null;
let simInterval = null;
let stompClient = null;
let isWsConnected = false;
let networkInterval = null;
let currentPosition = null;
let packetsSent = 0;
let raceInProgress = false;
let raceFinishedHandled = false;   // para mostrar el resultado una sola vez
let metaReachedNotified = false;   // aviso local "llegaste a la meta" (la victoria la confirma el servidor)
let lastSentFixTs = 0;             // timestamp del último fix GPS enviado (evita reenviar el mismo punto)
let statusPollInterval = null;
let reconnectTimer = null;
let wakeLock = null;
let estadoSalaFallando = false;

const mockWaypoints = [
    { lat: 4.782800, lng: -74.043000 },
    { lat: 4.783100, lng: -74.043200 },
    { lat: 4.783500, lng: -74.043400 },
    { lat: 4.783900, lng: -74.043600 },
    { lat: 4.784300, lng: -74.043800 },
    { lat: 4.784700, lng: -74.044000 },
    { lat: 4.785000, lng: -74.044200 },
    { lat: 4.785300, lng: -74.044500 }
];

// --- Simulador de ruta: camina por los waypoints a velocidad humana (~12 km/h) en ida y vuelta.
// Antes saltaba de waypoint en waypoint (~40 m) cada 300 ms (≈ 480 km/h) y el anti-trampas
// del servidor marcaba como sospechoso cada paquete del simulador.
const SIM_SPEED_MS = 3.3;   // metros por segundo (~12 km/h)
const SIM_TICK_MS = 300;
const MAX_ERROR_GPS_M = 50;   // fixes con más error que esto no se envían
let simMetros = 0;
let simDireccion = 1;
let simSegmentos = null;

function prepararRutaSimulada() {
    simSegmentos = [];
    let acumulado = 0;
    for (let i = 0; i < mockWaypoints.length - 1; i++) {
        const a = mockWaypoints[i], b = mockWaypoints[i + 1];
        const largo = calcularDistanciaMetros(a.lat, a.lng, b.lat, b.lng);
        simSegmentos.push({ a, b, inicio: acumulado, largo });
        acumulado += largo;
    }
    simSegmentos.total = acumulado;
}

function siguientePuntoSimulado() {
    if (!simSegmentos) prepararRutaSimulada();
    simMetros += simDireccion * SIM_SPEED_MS * (SIM_TICK_MS / 1000);
    if (simMetros >= simSegmentos.total) { simMetros = simSegmentos.total; simDireccion = -1; }
    if (simMetros <= 0) { simMetros = 0; simDireccion = 1; }
    const seg = simSegmentos.find(s => simMetros <= s.inicio + s.largo) || simSegmentos[simSegmentos.length - 1];
    const f = seg.largo > 0 ? (simMetros - seg.inicio) / seg.largo : 0;
    return { lat: seg.a.lat + (seg.b.lat - seg.a.lat) * f, lng: seg.a.lng + (seg.b.lng - seg.a.lng) * f };
}
let metaLatGlobal = null;
let metaLonGlobal = null;

// 🚀 Fórmula Haversine corregida (sin duplicados y con operadores *)
function calcularDistanciaMetros(lat1, lon1, lat2, lon2) {
    if (!lat1 || !lon1 || !lat2 || !lon2) return Infinity;
    const R = 6371e3; // Radio de la Tierra en metros
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

function log(msg, type = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    eventLog.appendChild(entry);
    eventLog.scrollTop = eventLog.scrollHeight;
}

// Payload mínimo: solo los campos que el backend (Ubication) realmente usa.
// El timestamp es el del FIX del GPS, no el de envío: así el anti-trampas calcula la
// velocidad sobre el tiempo real entre dos lecturas del sensor y no sobre el intervalo de envío.
function construirPayload(lat, lng, ts) {
    return {
        runnerId: runnerId,
        latitude: lat,
        longitude: lng,
        roomCode: roomCode,
        car: selectedCar,
        timestamp: ts || Date.now()
    };
}

function enviarTelemetria(payload) {
    if (!stompClient || !stompClient.connected || !isWsConnected) return false;
    try {
        stompClient.send("/app/telemetry", {}, JSON.stringify(payload));
        packetsSent++;
        if (packetsCountDisplay) packetsCountDisplay.innerText = packetsSent;
        return true;
    } catch (e) {
        isWsConnected = false;
        return false;
    }
}

// Mantiene la pantalla encendida: si el teléfono se bloquea, el navegador congela el GPS y el socket.
async function solicitarWakeLock() {
    try {
        if ('wakeLock' in navigator && !wakeLock) {
            wakeLock = await navigator.wakeLock.request('screen');
            wakeLock.addEventListener('release', () => { wakeLock = null; });
        }
    } catch (e) { /* no soportado o denegado: se ignora */ }
}
function liberarWakeLock() {
    try { if (wakeLock) { wakeLock.release(); wakeLock = null; } } catch (e) {}
}
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && (watchId !== null || simInterval !== null || window.simPathInterval)) {
        solicitarWakeLock();
    }
});

function mostrarNotificacionVictoria(winnerId, soyElGanador) {
    const viejo = document.getElementById('victoryBanner');
    if (viejo) viejo.remove();
    const banner = document.createElement('div');
    banner.id = 'victoryBanner';
    banner.style.cssText = `
        position: fixed; top: 16px; left: 50%; transform: translateX(-50%);
        width: min(92vw, 420px); box-sizing: border-box; text-align: center;
        padding: 1rem 1.25rem; border-radius: 12px; z-index: 9999; font-family: monospace;
        font-weight: bold; border: 3px solid #fff; box-shadow: 0 10px 30px rgba(0,0,0,0.7);
        ${soyElGanador
        ? 'background: linear-gradient(135deg, #FFD700, #FFA500); color: #000;'
        : 'background: #1c2024; color: #e8a33d;'}
    `;
    const nombre = String(winnerId || 'Nadie').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    banner.innerHTML = soyElGanador
        ? '🏆 ¡GANASTE LA CARRERA! 🏆'
        : `🏁 Carrera finalizada<br><span style="font-size:0.9rem;">Ganador: ${nombre}</span>`;
    document.body.appendChild(banner);
}

function detenerTodo() {
    if (watchId !== null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
    if (simInterval !== null) { clearInterval(simInterval); simInterval = null; }
    if (window.simPathInterval) { clearInterval(window.simPathInterval); window.simPathInterval = null; }
    detenerTransmisionContinua();
    liberarWakeLock();
}

// Punto único de entrada para el estado de la sala: lo usan el WebSocket Y el polling,
// así un teléfono que perdió el socket igual se entera de que la carrera empezó o terminó.
function procesarEstadoSala(room) {
    if (!room) return;
    if (room.metaLat && room.metaLon) {
        metaLatGlobal = room.metaLat;
        metaLonGlobal = room.metaLon;
    }
    const anterior = raceStateDisplay.textContent;
    applyGateState(room.status);

    if (room.status !== anterior) {
        log(`👥 Estado de la sala: ${room.status}`, 'info');
        if (room.status === 'IN_PROGRESS') log('🏁 ¡El host inició la carrera! GPS habilitado.', 'info');
    }

    if (room.status === 'WAITING' || room.status === 'IN_PROGRESS') {
        raceFinishedHandled = false;
    }

    if (room.status === 'FINISHED' && !raceFinishedHandled) {
        raceFinishedHandled = true;
        raceInProgress = false;
        raceStateDisplay.style.color = '#FFD700';
        log(`🏁 ¡Carrera finalizada! Ganador: ${room.winnerId || 'ninguno'}`, 'info');
        detenerTodo();
        hudStatus.textContent = "FINALIZADA";

        const soyElGanador = !!room.winnerId && room.winnerId.trim().toLowerCase() === runnerId.trim().toLowerCase();
        mostrarNotificacionVictoria(room.winnerId, soyElGanador);
        try { localStorage.removeItem('ecirace_username'); } catch (e) {}
    }
}

function applyGateState(status) {
    if (status === 'WAITING' || status === 'IN_PROGRESS') {
        const oldBanner = document.getElementById('victoryBanner');
        if (oldBanner) oldBanner.remove();
    }

    raceInProgress = (status === 'IN_PROGRESS');
    raceStateDisplay.textContent = status;
    raceStateDisplay.style.color = raceInProgress ? 'var(--color-teal)' : 'var(--color-amber)';
    raceGateCard.style.display = raceInProgress ? 'none' : 'block';

    const isTracking = (watchId !== null || simInterval !== null);
    btnStartGps.disabled = !raceInProgress || isTracking;
    btnToggleSim.disabled = !raceInProgress || isTracking;
}

async function consultarEstadoSala() {
    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`, {
            headers: {
                'ngrok-skip-browser-warning': 'true',
                'Accept': 'application/json'
            }
        });
        if (res.ok) {
            procesarEstadoSala(await res.json());
        } else {
            applyGateState('WAITING');
        }
        estadoSalaFallando = false;
    } catch (e) {
        if (!estadoSalaFallando) log('No se pudo consultar el estado de la sala, reintentando...', 'warn');
        estadoSalaFallando = true;
    }
}

function programarReconexion() {
    if (reconnectTimer !== null) return;       // evita acumular varios reintentos
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        conectarWebSocket();
    }, 3000);
}

function conectarWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    const wsUrl = wsProtocol + window.location.host + '/race-ws-native';

    const socket = new WebSocket(wsUrl);
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function () {
        isWsConnected = true;
        if (wsStatusDisplay) {
            wsStatusDisplay.innerText = "CONECTADO (En Vivo)";
            wsStatusDisplay.style.color = "var(--color-teal)";
        }
        log(`Conexión WebSocket establecida para sala ${roomCode}.`, "info");

        stompClient.subscribe(`/topic/rooms/${roomCode}/status`, (msg) => {
            procesarEstadoSala(JSON.parse(msg.body));
        });

        // Si se perdió el socket durante la carrera, nos ponemos al día con el estado actual
        consultarEstadoSala();
    }, function () {
        isWsConnected = false;
        // stomp.js llama a este callback tanto si falla la conexión inicial como si se cae el socket después
        if (wsStatusDisplay) {
            wsStatusDisplay.innerText = "RECONECTANDO...";
            wsStatusDisplay.style.color = "var(--color-alert)";
        }
        programarReconexion();
    });
}

function iniciarTransmisionContinua() {
    if (networkInterval !== null) clearInterval(networkInterval);
    networkInterval = setInterval(() => {
        if (!isWsConnected || !currentPosition || !raceInProgress) return;
        // Solo se envía cuando hay un fix nuevo (el GPS entrega ~1 por segundo; antes se
        // reenviaba la misma posición cada 300 ms y se multiplicaba el tráfico por cada teléfono).
        if (currentPosition.ts === lastSentFixTs) return;
        if (enviarTelemetria(construirPayload(currentPosition.lat, currentPosition.lng, currentPosition.ts))) {
            lastSentFixTs = currentPosition.ts;
        }
    }, 300);
}

function detenerTransmisionContinua() {
    if (networkInterval !== null) {
        clearInterval(networkInterval);
        networkInterval = null;
    }
}

function updateUI(lat, lng, acc, speed, source, fixTs) {
    currentPosition = { lat, lng, ts: fixTs || Date.now() };
    hudStatus.textContent = "GPS ACTIVO";
    hudStatus.style.color = "var(--color-teal)";
    sourceBadge.textContent = source;
    sourceBadge.style.color = source.includes('SIMULA') ? 'var(--color-amber)' : 'var(--color-teal)';
    latDisplay.textContent = lat.toFixed(6);
    lngDisplay.textContent = lng.toFixed(6);
    accDisplay.textContent = acc ? `± ${acc} m` : 'N/A';
    speedDisplay.textContent = speed.toFixed(1);
    timeDisplay.textContent = new Date().toLocaleTimeString();

    // La meta la valida el SERVIDOR (el primero que llegue gana, sin importar el reloj de cada teléfono).
    // Aquí solo avisamos y seguimos enviando la posición hasta que llegue el estado FINISHED.
    if (raceInProgress && !metaReachedNotified && metaLatGlobal && metaLonGlobal) {
        const distancia = calcularDistanciaMetros(lat, lng, metaLatGlobal, metaLonGlobal);
        if (distancia <= 15.0) {
            metaReachedNotified = true;
            log(`🏁 Estás a ${distancia.toFixed(1)} m de la meta. Esperando confirmación del servidor...`, 'info');
            hudStatus.textContent = "EN LA META";
        }
    }
}

btnStartGps.addEventListener('click', () => {
    if (!raceInProgress) {
        alert('El host todavía no ha iniciado la carrera.');
        return;
    }
    if (!("geolocation" in navigator)) {
        alert("Geolocalización no soportada en este navegador.");
        return;
    }
    btnStartGps.disabled = true;
    btnStopGps.disabled = false;
    btnToggleSim.disabled = true;
    log("Iniciando sensor GPS de alta precisión...", "info");
    if (!window.isSecureContext) {
        log("⚠️ Esta página no es HTTPS: el navegador del celular bloqueará el GPS. Abre la app por el enlace https (ngrok) o usa el simulador.", "warn");
    }
    solicitarWakeLock();

    watchId = navigator.geolocation.watchPosition((pos) => {
        // Un fix con mucho error (típico al inicio o bajo techo) hace "saltar" al carro por el mapa
        // y dispara el anti-trampas del servidor. Se descarta hasta que el GPS se estabilice.
        if (pos.coords.accuracy > MAX_ERROR_GPS_M) {
            log(`GPS poco preciso (± ${Math.round(pos.coords.accuracy)} m), esperando una señal mejor...`, 'warn');
            return;
        }
        const speedKmh = pos.coords.speed !== null && !isNaN(pos.coords.speed) ? pos.coords.speed * 3.6 : 0.0;
        updateUI(pos.coords.latitude, pos.coords.longitude, Math.round(pos.coords.accuracy), speedKmh, 'GPS_HARDWARE', pos.timestamp);
    }, (err) => {
        log("Error en GPS: " + err.message + (err.code === 1 ? " (permiso denegado: actívalo en los ajustes del navegador)" : ""), "error");
    }, {
        enableHighAccuracy: true,
        maximumAge: 0,
        timeout: 10000
    });

    iniciarTransmisionContinua();
});

btnStopGps.addEventListener('click', () => {
    if (watchId !== null) {
        navigator.geolocation.clearWatch(watchId);
        watchId = null;
    }
    if (simInterval !== null) {
        clearInterval(simInterval);
        simInterval = null;
    }
    detenerTransmisionContinua();
    liberarWakeLock();
    btnStartGps.disabled = !raceInProgress;
    btnToggleSim.disabled = !raceInProgress;
    btnStopGps.disabled = true;
    hudStatus.textContent = "STANDBY";
    hudStatus.style.color = "var(--color-alert)";
    btnToggleSim.textContent = "🎮 Activar Simulador de Ruta";
    log("Rastreo detenido.", "warn");
});

btnToggleSim.addEventListener('click', () => {
    if (!raceInProgress) {
        alert('El host todavía no ha iniciado la carrera.');
        return;
    }
    if (simInterval !== null) {
        clearInterval(simInterval);
        simInterval = null;
        detenerTransmisionContinua();
        btnStartGps.disabled = !raceInProgress;
        btnStopGps.disabled = true;
        btnToggleSim.textContent = "🎮 Activar Simulador de Ruta";
        hudStatus.textContent = "STANDBY";
        log("Simulador detenido.", "warn");
    } else {
        if (watchId !== null) navigator.geolocation.clearWatch(watchId);
        btnStartGps.disabled = true;
        btnStopGps.disabled = false;
        btnToggleSim.textContent = "⏹️ Detener Simulador";
        log("Simulador de ruta iniciado.", "info");
        solicitarWakeLock();

        simInterval = setInterval(() => {
            const p = siguientePuntoSimulado();
            updateUI(p.lat, p.lng, 5, SIM_SPEED_MS * 3.6, 'GPS_SIMULADOR');
        }, SIM_TICK_MS);
        iniciarTransmisionContinua();
    }
});

// --- SIMULADOR DE VICTORIA DEFINITIVO ---
const btnSimulateWin = document.getElementById('btnSimulateWin');

if (btnSimulateWin) {
    btnSimulateWin.addEventListener('click', async () => {
        if (!raceInProgress) {
            alert("⚠️ ERROR: El host debe Iniciar la Carrera primero desde el Dashboard.");
            return;
        }

        try {
            let targetLat = 4.782800;
            let targetLon = -74.043000;

            try {
                const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`, {
                    headers: {
                        'ngrok-skip-browser-warning': 'true',
                        'Accept': 'application/json'
                    }
                });
                if (res.ok) {
                    const room = await res.json();
                    if (room.metaLat && room.metaLon) {
                        targetLat = room.metaLat;
                        targetLon = room.metaLon;
                    }
                }
            } catch (err) {}

            btnSimulateWin.disabled = true;
            btnSimulateWin.textContent = "⏳ Posicionando...";
            if (watchId !== null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
            if (simInterval !== null) { clearInterval(simInterval); simInterval = null; }
            detenerTransmisionContinua();
            if (window.simPathInterval) clearInterval(window.simPathInterval);

            let startLat = targetLat - 0.0001;
            let startLon = targetLon - 0.0001;

            enviarTelemetria(construirPayload(startLat, startLon));
            updateUI(startLat, startLon, 1, 0, 'ENFRIANDO RADAR...');
            solicitarWakeLock();

            setTimeout(() => {
                btnSimulateWin.textContent = "🏁 Trotando a la meta (11 km/h)...";

                let steps = 20;
                let currentStep = 0;

                window.simPathInterval = setInterval(() => {
                    currentStep++;
                    let fraction = currentStep / steps;

                    let lat = startLat + (targetLat - startLat) * fraction;
                    let lon = startLon + (targetLon - startLon) * fraction;

                    if (currentStep >= steps) {
                        lat = targetLat;
                        lon = targetLon;
                    }

                    updateUI(lat, lon, 2, 11.3, 'SIMULACION_LEGAL');
                    enviarTelemetria(construirPayload(lat, lon));

                    if (currentStep >= steps) {
                        clearInterval(window.simPathInterval);
                        window.simPathInterval = null;
                        // La victoria NO se declara aquí: la confirma el servidor para todos los dispositivos a la vez
                        btnSimulateWin.textContent = "🏁 En la meta, esperando confirmación...";
                    }
                }, 250);
            }, 2000);

        } catch (e) {
            console.error(e);
            btnSimulateWin.disabled = false;
            btnSimulateWin.textContent = "🏁 Simular Victoria";
        }
    });
}

conectarWebSocket();
consultarEstadoSala();
// Respaldo por si el WebSocket se cae justo cuando cambia el estado (inicio / fin de carrera)
statusPollInterval = setInterval(consultarEstadoSala, 4000);