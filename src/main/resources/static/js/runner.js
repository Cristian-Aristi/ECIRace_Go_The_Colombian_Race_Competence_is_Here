// Capa visual de juego (game-ui.js), con respaldo si no cargara
const UI = window.GameUI || {
    toast: (m) => alert(m),
    setLabel: (b, t) => { if (b) b.textContent = t; },
    countdown: () => Promise.resolve(),
    confetti: () => {},
    setSceneSpeed: () => {},
    sfx: { win() {}, lose() {}, coin() {}, error() {} },
    settings: { highAccuracy: true },
    icon: () => ''
};

const urlParams = new URLSearchParams(window.location.search);
const roomCode = Session.validRoomCode(urlParams.get('room')) || 'CAMPUS-ECI';
let selectedCar = urlParams.get('car');

// El piloto es el de la sesión emitida por el servidor (ya no se acepta ?player= en la URL).
// El servidor además ignora cualquier runnerId que mande el cliente: usa el del token.
if (!Session.requireLogin(roomCode)) {
    throw new Error('Sin sesión: redirigiendo al inicio');
}
const runnerId = Session.username();

try {
    if (!selectedCar) {
        selectedCar = localStorage.getItem('ecirace_car') || localStorage.getItem('selectedCar');
    }
} catch (e) {
    console.warn("Memoria local restringida por el navegador.");
}
// Solo se aceptan los valores del enum VehicleType (los mismos que usa el Lobby y el Dashboard)
const VEHICULOS_VALIDOS = ['RENAULT_4', 'CHEVROLET_SPARK', 'MAZDA_323'];
selectedCar = (selectedCar || '').trim().toUpperCase();
if (!VEHICULOS_VALIDOS.includes(selectedCar)) selectedCar = 'RENAULT_4';

try {
    localStorage.setItem('ecirace_car', selectedCar);
} catch (e) {}

// ---- Carro elegido en el HUD ----
const IMAGEN_CARRO = { RENAULT_4: 'img/renault.png?v=2', CHEVROLET_SPARK: 'img/spark.png?v=2', MAZDA_323: 'img/mazda.png?v=2' };
['driverCarImg', 'trackCarImg'].forEach(id => {
    const img = document.getElementById(id);
    if (img) img.src = IMAGEN_CARRO[selectedCar] || IMAGEN_CARRO.RENAULT_4;
});

// ---- Velocímetro arcade (0-40 km/h; desde 30 km/h el anti-trampas sospecha) ----
const VEL_MAX = 40;
let agujaVelocimetro = null;
(function construirVelocimetro() {
    const svg = document.getElementById('speedoSvg');
    if (!svg) return;
    const cx = 150, cy = 150, r = 118, segmentos = 24;
    let html = `<path d="M ${cx - 132} ${cy} A 132 132 0 0 1 ${cx + 132} ${cy}" fill="none" stroke="#050b22" stroke-width="30" stroke-linecap="butt"/>`;
    for (let i = 0; i < segmentos; i++) {
        const a0 = Math.PI + (i / segmentos) * Math.PI + 0.02;
        const a1 = Math.PI + ((i + 1) / segmentos) * Math.PI - 0.02;
        const v = (i + 0.5) / segmentos * VEL_MAX;
        const color = v >= 30 ? '#ff4b55' : (v >= 20 ? '#ffc928' : '#3fcf6a');
        const p = (a, rr) => `${(cx + rr * Math.cos(a)).toFixed(1)} ${(cy + rr * Math.sin(a)).toFixed(1)}`;
        html += `<path d="M ${p(a0, r + 10)} A ${r + 10} ${r + 10} 0 0 1 ${p(a1, r + 10)} L ${p(a1, r - 10)} A ${r - 10} ${r - 10} 0 0 0 ${p(a0, r - 10)} Z" fill="${color}" stroke="#050b22" stroke-width="2" shape-rendering="crispEdges"/>`;
    }
    for (let k = 0; k <= VEL_MAX; k += 10) {
        const a = Math.PI + (k / VEL_MAX) * Math.PI;
        const tx = cx + (r - 32) * Math.cos(a), ty = cy + (r - 32) * Math.sin(a);
        html += `<text x="${tx.toFixed(1)}" y="${(ty + 6).toFixed(1)}" text-anchor="middle" font-family="VT323, monospace" font-size="22" fill="#c6d6ff">${k}</text>`;
    }
    html += `<g id="g-needle"><rect x="${cx - 3}" y="${cy - r + 6}" width="6" height="${r - 6}" fill="#ffffff" stroke="#050b22" stroke-width="2"/>`
        + `<rect x="${cx - 3}" y="${cy - r + 6}" width="6" height="14" fill="#ff4b55"/></g>`
        + `<rect x="${cx - 12}" y="${cy - 12}" width="24" height="24" fill="#ffc928" stroke="#050b22" stroke-width="3"/>`;
    svg.innerHTML = html;
    agujaVelocimetro = document.getElementById('g-needle');
    moverAguja(0);
})();

function moverAguja(kmh) {
    if (!agujaVelocimetro) return;
    const v = Math.max(0, Math.min(VEL_MAX, kmh || 0));
    agujaVelocimetro.style.transform = `rotate(${-90 + (v / VEL_MAX) * 180}deg)`;
}

// ---- Barra de pista: el carro avanza según la distancia real a la meta ----
let distanciaInicialMeta = null;
let ultimoFixGps = null;
function actualizarPistaMeta(lat, lng) {
    const etiqueta = document.getElementById('distMetaDisplay');
    const carro = document.getElementById('trackCarImg');
    if (!metaLatGlobal || !metaLonGlobal || !etiqueta || !carro) return;
    const d = calcularDistanciaMetros(lat, lng, metaLatGlobal, metaLonGlobal);
    if (!isFinite(d)) return;
    if (distanciaInicialMeta === null || d > distanciaInicialMeta) distanciaInicialMeta = Math.max(d, 1);
    const avance = Math.max(0, Math.min(1, 1 - d / distanciaInicialMeta));
    carro.style.left = `${4 + avance * 88}%`;
    etiqueta.textContent = d >= 1000 ? `${(d / 1000).toFixed(2)} km` : `${Math.round(d)} m`;
}
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
    const nombre = String(winnerId || 'Nadie').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const overlay = document.createElement('div');
    overlay.id = 'victoryBanner';
    overlay.className = 'g-result' + (soyElGanador ? '' : ' g-result--lose');
    overlay.setAttribute('role', 'alertdialog');
    overlay.setAttribute('aria-modal', 'true');
    const volver = 'lobby.html';
    const tablero = `dashboard.html?room=${encodeURIComponent(roomCode)}`;
    overlay.innerHTML = `
        <div class="g-result__card g-panel">
            <div class="g-result__trophy">${UI.icon(soyElGanador ? 'trophy' : 'flag')}</div>
            <h2 class="g-result__title">${soyElGanador ? '🏆 ¡GANASTE LA CARRERA! 🏆' : 'Carrera finalizada'}</h2>
            <div class="g-result__sub">${soyElGanador
        ? 'Llegaste primero a la meta. ¡Victoria sumada a tu perfil!'
        : `Ganador: <b>${nombre}</b>`}</div>
            <a class="g-btn g-btn--gold" href="${volver}">Volver al garaje</a>
            <a class="g-btn" href="${tablero}">Ver el tablero de la sala</a>
        </div>`;
    document.body.appendChild(overlay);
    if (soyElGanador) { UI.sfx.win(); UI.confetti(4000); } else { UI.sfx.lose(); }
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
        if (room.status === 'IN_PROGRESS') {
            log('🏁 ¡El host inició la carrera! GPS habilitado.', 'info');
            mostrarCuentaRegresiva();
        }
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

        const soyElGanador = !!room.winnerId && room.winnerId === runnerId;
        mostrarNotificacionVictoria(room.winnerId, soyElGanador);
    }
}

function mostrarCuentaRegresiva() {
    const clave = 'ecirace_countdown_' + roomCode;
    try { if (sessionStorage.getItem(clave)) return; sessionStorage.setItem(clave, '1'); } catch (e) {}
    UI.countdown().then(() => UI.toast('¡Arranca! Pulsa "Iniciar GPS real" y corre a la meta.', 'ok'));
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
        const res = await Session.api(`/api/rooms/${encodeURIComponent(roomCode)}`);
        if (res.ok) {
            procesarEstadoSala(res.data);
        } else if (res.status === 401) {
            Session.requireLogin(roomCode);
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

    stompClient.connect(Session.stompHeaders(), function () {
        isWsConnected = true;
        if (wsStatusDisplay) {
            wsStatusDisplay.innerText = "CONECTADO (En Vivo)";
            wsStatusDisplay.className = 'g-pill g-pill--teal';
            wsStatusDisplay.style.color = "";
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
            wsStatusDisplay.className = 'g-pill g-pill--red';
            wsStatusDisplay.style.color = "";
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
    moverAguja(speed);
    UI.setSceneSpeed(speed);
    actualizarPistaMeta(lat, lng);

    // La meta la valida el SERVIDOR (el primero que llegue gana, sin importar el reloj de cada teléfono).
    // Aquí solo avisamos y seguimos enviando la posición hasta que llegue el estado FINISHED.
    if (raceInProgress && !metaReachedNotified && metaLatGlobal && metaLonGlobal) {
        const distancia = calcularDistanciaMetros(lat, lng, metaLatGlobal, metaLonGlobal);
        if (distancia <= 15.0) {
            metaReachedNotified = true;
            log(`🏁 Estás a ${distancia.toFixed(1)} m de la meta. Esperando confirmación del servidor...`, 'info');
            hudStatus.textContent = "EN LA META";
            UI.toast('🏁 ¡Estás en la meta! Esperando la confirmación del servidor...', 'ok');
        }
    }
}

btnStartGps.addEventListener('click', () => {
    if (!raceInProgress) {
        UI.toast('El host todavía no ha iniciado la carrera.', 'warn');
        return;
    }
    if (!("geolocation" in navigator)) {
        UI.toast("Este navegador no tiene GPS. Usa el simulador de ruta.", 'error');
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
        let speedKmh = pos.coords.speed !== null && !isNaN(pos.coords.speed) ? pos.coords.speed * 3.6 : null;
        // Algunos teléfonos no reportan velocidad: se estima con la lectura anterior del GPS
        if (speedKmh === null) {
            speedKmh = 0;
            if (ultimoFixGps && pos.timestamp > ultimoFixGps.ts) {
                const seg = (pos.timestamp - ultimoFixGps.ts) / 1000;
                const metros = calcularDistanciaMetros(ultimoFixGps.lat, ultimoFixGps.lng, pos.coords.latitude, pos.coords.longitude);
                if (seg >= 0.5 && isFinite(metros)) speedKmh = Math.min(99, (metros / seg) * 3.6);
            }
        }
        ultimoFixGps = { lat: pos.coords.latitude, lng: pos.coords.longitude, ts: pos.timestamp };
        updateUI(pos.coords.latitude, pos.coords.longitude, Math.round(pos.coords.accuracy), speedKmh, 'GPS_HARDWARE', pos.timestamp);
    }, (err) => {
        log("Error en GPS: " + err.message + (err.code === 1 ? " (permiso denegado: actívalo en los ajustes del navegador)" : ""), "error");
    }, {
        enableHighAccuracy: UI.settings.highAccuracy !== false,
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
    UI.setLabel(btnToggleSim, "Activar simulador de ruta");
    UI.setSceneSpeed(0);
    moverAguja(0);
    log("Rastreo detenido.", "warn");
});

btnToggleSim.addEventListener('click', () => {
    if (!raceInProgress) {
        UI.toast('El host todavía no ha iniciado la carrera.', 'warn');
        return;
    }
    if (simInterval !== null) {
        clearInterval(simInterval);
        simInterval = null;
        detenerTransmisionContinua();
        btnStartGps.disabled = !raceInProgress;
        btnStopGps.disabled = true;
        UI.setLabel(btnToggleSim, "Activar simulador de ruta");
        hudStatus.textContent = "STANDBY";
        log("Simulador detenido.", "warn");
    } else {
        if (watchId !== null) navigator.geolocation.clearWatch(watchId);
        btnStartGps.disabled = true;
        btnStopGps.disabled = false;
        UI.setLabel(btnToggleSim, "Detener simulador");
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
            UI.toast("El host debe iniciar la carrera primero.", 'warn');
            return;
        }

        try {
            let targetLat = 4.782800;
            let targetLon = -74.043000;

            try {
                const res = await Session.api(`/api/rooms/${encodeURIComponent(roomCode)}`);
                if (res.ok) {
                    const room = res.data;
                    if (room.metaLat && room.metaLon) {
                        targetLat = room.metaLat;
                        targetLon = room.metaLon;
                    }
                }
            } catch (err) {}

            btnSimulateWin.disabled = true;
            UI.setLabel(btnSimulateWin, "Posicionando...");
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
                UI.setLabel(btnSimulateWin, "Trotando a la meta (11 km/h)...");

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
                        UI.setLabel(btnSimulateWin, "En la meta, esperando confirmación...");
                    }
                }, 250);
            }, 2000);

        } catch (e) {
            console.error(e);
            btnSimulateWin.disabled = false;
            UI.setLabel(btnSimulateWin, "Simular victoria (ir a la meta)");
        }
    });
}

// Herramientas de demostración (simulador de ruta y "simular victoria"): solo si el servidor las
// habilita con ECIRACE_DEMO_TOOLS=true. En producción permitirían ganar sin moverse.
['btnToggleSim', 'btnSimulateWin'].forEach(id => {
    const b = document.getElementById(id);
    if (b) b.style.display = 'none';
});
Session.config().then(cfg => {
    if (!cfg.demoTools) return;
    ['btnToggleSim', 'btnSimulateWin'].forEach(id => {
        const b = document.getElementById(id);
        if (b) b.style.display = '';
    });
});

conectarWebSocket();
consultarEstadoSala();
// Respaldo por si el WebSocket se cae justo cuando cambia el estado (inicio / fin de carrera)
statusPollInterval = setInterval(consultarEstadoSala, 4000);