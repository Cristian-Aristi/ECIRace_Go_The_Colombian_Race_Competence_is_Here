const urlParams = new URLSearchParams(window.location.search);
let runnerId = urlParams.get('player') || localStorage.getItem('ecirace_username');
if (!runnerId || runnerId.trim() === '') {
    alert("Acceso denegado: Debes iniciar sesión en el Lobby antes de ingresar a la pista.");
    window.location.href = "index.html";
}
runnerId = runnerId.trim();
localStorage.setItem('ecirace_username', runnerId);
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
let mockIndex = 0;
let metaLatGlobal = null;
let metaLonGlobal = null;

function calcularDistanciaMetros(lat1, lon1, lat2, lon2) {
    if (!lat1 || !lon1 || !lat2 || !lon2) return Infinity;
    const R = 6371e3; // Radio de la Tierra en metros
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon/2) * Math.sin(dLon/2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    return R * c;
}

function log(msg, type = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    eventLog.appendChild(entry);
    eventLog.scrollTop = eventLog.scrollHeight;
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
                'ngrok-skip-browser-warning': 'true', // 🚀 Bypass de seguridad para Ngrok
                'Accept': 'application/json'
            }
        });
        if (res.ok) {
            const room = await res.json();
            applyGateState(room.status);
        } else {
            applyGateState('WAITING');
        }
    } catch (e) {
        log('No se pudo consultar el estado de la sala, reintentando...', 'warn');
    }
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
            const room = JSON.parse(msg.body);
            if (room.metaLat && room.metaLon) {
                metaLatGlobal = room.metaLat;
                metaLonGlobal = room.metaLon;
            }
            applyGateState(room.status);

            log(`👥 Estado actualizado de la sala: ${room.status}`, 'info');

            if (room.status === 'IN_PROGRESS') {
                log('🏁 ¡El host inició la carrera! GPS habilitado.', 'info');
            }
            else if (room.status === 'FINISHED') {
                log(`🏁 ¡Carrera finalizada! Ganador: ${room.winnerId}`, 'info');

                if (watchId !== null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
                if (simInterval !== null) { clearInterval(simInterval); simInterval = null; }
                detenerTransmisionContinua();

                const soyElGanador = room.winnerId && room.winnerId.trim().toLowerCase() === runnerId.trim().toLowerCase();
                mostrarNotificacionVictoria(room.winnerId, soyElGanador);

                localStorage.removeItem('ecirace_username');
            }
        });
    }, function () {
        isWsConnected = false;
        if (wsStatusDisplay) {
            wsStatusDisplay.innerText = "DESCONECTADO";
            wsStatusDisplay.style.color = "var(--color-alert)";
        }
        setTimeout(conectarWebSocket, 5000);
    });
}

function iniciarTransmisionContinua() {
    if (networkInterval !== null) clearInterval(networkInterval);
    networkInterval = setInterval(() => {
        if (isWsConnected && currentPosition && raceInProgress) {
            const payload = {
                corredorId: runnerId,
                runnerId: runnerId,
                latitud: currentPosition.lat,
                latitude: currentPosition.lat,
                longitud: currentPosition.lng,
                longitude: currentPosition.lng,
                roomCode: roomCode,
                timestamp: Date.now()
            };
            stompClient.send("/app/telemetry", {}, JSON.stringify(payload));
            packetsSent++;
            if (packetsCountDisplay) packetsCountDisplay.innerText = packetsSent;
        }
    }, 300); // 🚀 300ms
}

function detenerTransmisionContinua() {
    if (networkInterval !== null) {
        clearInterval(networkInterval);
        networkInterval = null;
    }
}

let metaLatGlobal = null;
let metaLonGlobal = null;

function calcularDistanciaMetros(lat1, lon1, lat2, lon2) {
    if (!lat1 || !lon1 || !lat2 || !lon2) return Infinity;
    const R = 6371e3;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon/2) * Math.sin(dLon/2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    return R * c;
}

function updateUI(lat, lng, acc, speed, source) {
    currentPosition = { lat, lng };
    hudStatus.textContent = "GPS ACTIVO";
    hudStatus.style.color = "var(--color-teal)";
    sourceBadge.textContent = source;
    sourceBadge.style.color = source.includes('SIMULA') ? 'var(--color-amber)' : 'var(--color-teal)';
    latDisplay.textContent = lat.toFixed(6);
    lngDisplay.textContent = lng.toFixed(6);
    accDisplay.textContent = acc ? `± ${acc} m` : 'N/A';
    speedDisplay.textContent = speed.toFixed(1);
    timeDisplay.textContent = new Date().toLocaleTimeString();

    if (raceInProgress && metaLatGlobal && metaLonGlobal) {
        const distancia = calcularDistanciaMetros(lat, lng, metaLatGlobal, metaLonGlobal);

        if (distancia <= 15.0 && raceStateDisplay.textContent !== 'FINISHED') {
            log(`🏁 ¡Radar a bordo detectó la meta a ${distancia.toFixed(1)} metros!`, 'info');

            raceInProgress = false;
            if (watchId !== null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
            if (simInterval !== null) { clearInterval(simInterval); simInterval = null; }
            if (window.simPathInterval) { clearInterval(window.simPathInterval); window.simPathInterval = null; }
            detenerTransmisionContinua();

            raceStateDisplay.textContent = 'FINISHED';
            raceStateDisplay.style.color = '#FFD700';
            mostrarNotificacionVictoria(runnerId, true);
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

    watchId = navigator.geolocation.watchPosition((pos) => {
        const speedKmh = pos.coords.speed !== null && !isNaN(pos.coords.speed) ? pos.coords.speed * 3.6 : 0.0;
        updateUI(pos.coords.latitude, pos.coords.longitude, Math.round(pos.coords.accuracy), speedKmh, 'GPS_HARDWARE');
    }, (err) => {
        log("Error en GPS: " + err.message, "error");
    }, {
        enableHighAccuracy: true, // 🚀 GPS Físico activado
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

        simInterval = setInterval(() => {
            const wp = mockWaypoints[mockIndex];
            const jitter = (Math.random() - 0.5) * 0.00004;
            const simulatedSpeed = 12.0 + Math.random() * 4.0;
            updateUI(wp.lat + jitter, wp.lng + jitter, 5, simulatedSpeed, 'GPS_SIMULADOR');
            mockIndex = (mockIndex + 1) % mockWaypoints.length;
        }, 300); // 🚀 300ms
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
                const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`);
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

            const primerPayload = {
                corredorId: runnerId, runnerId: runnerId,
                latitud: startLat, latitude: startLat,
                longitud: startLon, longitude: startLon,
                roomCode: roomCode, timestamp: Date.now()
            };
            if (stompClient && stompClient.connected) {
                stompClient.send("/app/telemetry", {}, JSON.stringify(primerPayload));
            }
            updateUI(startLat, startLon, 1, 0, 'ENFRIANDO RADAR...');

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

                    stompClient.send("/app/telemetry", {}, JSON.stringify({
                        corredorId: runnerId, runnerId: runnerId,
                        latitud: lat, latitude: lat,
                        longitud: lon, longitude: lon,
                        roomCode: roomCode, timestamp: Date.now()
                    }));

                    if (currentStep >= steps) {
                        clearInterval(window.simPathInterval);
                        btnSimulateWin.textContent = "🏆 Línea cruzada exacta";


                        setTimeout(() => {
                            if (raceStateDisplay.textContent !== 'FINISHED') {
                                raceStateDisplay.textContent = 'FINISHED';
                                raceStateDisplay.style.color = '#FFD700';
                                mostrarNotificacionVictoria(runnerId, true);
                            }
                        }, 500);
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