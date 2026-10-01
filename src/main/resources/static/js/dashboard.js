const urlParams = new URLSearchParams(window.location.search);
const roomCode = (urlParams.get('room') || 'CAMPUS-ECI').toUpperCase();
document.getElementById('roomCodeDisplay').innerText = roomCode;

const campusCenter = [4.782800, -74.043000];
const map = L.map('map').setView(campusCenter, 16);

// --- GESTIÓN DE LA META INTERACTIVA EN EL MAPA ---
let metaCoordenadas = [4.782800, -74.043000];
let metaLayer = null;


metaLayer = L.circle(metaCoordenadas, {
    color: '#FFD700',
    fillColor: '#FFD700',
    fillOpacity: 0.25,
    weight: 3,
    dashArray: '6, 6'
}).addTo(map);

metaLayer.bindPopup("<div style='text-align:center;'><b>🏁 LÍNEA DE LLEGADA</b><br>Haz clic en el mapa para mover la meta (Solo en modo WAITING)</div>");

// Variable de control para saber si el modo edición de meta está activo
let settingMetaMode = false;

const btnToggleMetaMode = document.getElementById('btnToggleMetaMode');
const metaModeStatus = document.getElementById('metaModeStatus');

// Función auxiliar para actualizar la apariencia visual del botón
function actualizarBotonMetaUI() {
    if (!btnToggleMetaMode || !metaModeStatus) return;

    if (settingMetaMode) {
        btnToggleMetaMode.style.borderColor = 'var(--color-amber)';
        metaModeStatus.textContent = 'ACTIVADO (Haz clic en el mapa)';
        metaModeStatus.style.color = 'var(--color-amber)';
    } else {
        btnToggleMetaMode.style.borderColor = 'var(--border-line)';
        metaModeStatus.textContent = 'DESACTIVADO';
        metaModeStatus.style.color = 'var(--color-alert)';
    }
}

// 1. Evento para habilitar / deshabilitar el modo manualmente con el botón
if (btnToggleMetaMode) {
    btnToggleMetaMode.addEventListener('click', () => {
        // Validación: Solo se puede cambiar la meta si la carrera está en espera
        if (raceStatusValue && raceStatusValue.textContent !== 'WAITING') {
            alert("⚠️ No puedes cambiar la meta una vez que la carrera ha comenzado.");
            return;
        }

        // Alternar el estado (True a False, o False a True)
        settingMetaMode = !settingMetaMode;
        actualizarBotonMetaUI();
    });
}

// 2. Evento de clic en el mapa (Solo actualiza la meta si el modo está ACTIVADO, sin apagar el botón)
map.on('click', function(e) {
    if (raceStatusValue && raceStatusValue.textContent === 'WAITING' && settingMetaMode) {
        metaCoordenadas = [e.latlng.lat, e.latlng.lng];
        metaLayer.setLatLng(metaCoordenadas); // Mueve el círculo dorado visualmente

        // Notificación visual en el mapa (Popup de Leaflet)
        L.popup()
            .setLatLng(e.latlng)
            .setContent("<b>🏁 ¡Meta Actualizada!</b><br>Puedes seguir haciendo clic para reubicarla o apagar el botón.")
            .openOn(map);

        // 🚀 Nota: Ya NO apagamos settingMetaMode aquí. El botón se queda activo
        // hasta que tú decidas presionar el botón de nuevo para desactivarlo.
    }
});

L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap - ECIRace GO'
}).addTo(map);

if ("geolocation" in navigator) {
    navigator.geolocation.getCurrentPosition(function(pos) {
        L.circleMarker([pos.coords.latitude, pos.coords.longitude], {
            color: '#e8a33d', fillColor: '#e8a33d', fillOpacity: 0.85, radius: 9
        }).addTo(map).bindPopup(`<b>🏁 Central de Control (${roomCode})</b>`).openPopup();
    }, function() {
        L.circleMarker(campusCenter, {
            color: '#e8a33d', fillColor: '#e8a33d', fillOpacity: 0.85, radius: 9
        }).addTo(map).bindPopup(`<b>🏁 Central Campus ECI (${roomCode})</b>`);
    });
}

const corredores = {};
let stompClient = null;
let hostNameDeLaSala = null;
let ultimoRosterConocido = [];

const statusDisplay = document.getElementById('status');
const hostStatusBadge = document.getElementById('hostStatusBadge');
const activeCountDisplay = document.getElementById('activeCount');
const runnersList = document.getElementById('runnersList');
const raceStatusValue = document.getElementById('raceStatusValue');
const btnStartRaceDashboard = document.getElementById('btnStartRaceDashboard');
const eventLog = document.getElementById('eventLog');

function log(msg, type = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    eventLog.appendChild(entry);
    eventLog.scrollTop = eventLog.scrollHeight;
}

function conectarWebSocket() {
    const socket = new SockJS('/race-ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function () {
        statusDisplay.innerText = "En Vivo (Conectado)";
        statusDisplay.style.color = "var(--color-teal)";
        hostStatusBadge.innerText = "ONLINE";
        hostStatusBadge.style.color = "var(--color-teal)";
        hostStatusBadge.style.borderColor = "var(--color-teal)";

        stompClient.subscribe('/topic/rooms/' + roomCode, function (mensaje) {
            const ubication = JSON.parse(mensaje.body);
            actualizarMapaYLista(ubication);
        });

        stompClient.subscribe('/topic/broadcast', function (mensaje) {
            const ubication = JSON.parse(mensaje.body);
            if ((ubication.roomCode || 'CAMPUS-ECI').toUpperCase() === roomCode) actualizarMapaYLista(ubication);
        });

        stompClient.subscribe('/topic/rooms/' + roomCode + '/status', function (mensaje) {
            aplicarEstadoDeSala(JSON.parse(mensaje.body));
        });

    }, function () {
        statusDisplay.innerText = "Desconectado. Reintentando...";
        statusDisplay.style.color = "var(--color-alert)";
        hostStatusBadge.innerText = "OFFLINE";
        hostStatusBadge.style.color = "var(--color-alert)";
        setTimeout(conectarWebSocket, 5000);
    });
}

function actualizarMapaYLista(ubication) {
    const id = ubication.runnerId;
    const lat = ubication.latitude;
    const lon = ubication.longitude;
    const speed = (ubication.speed !== undefined) ? ubication.speed : 0.0;
    const isSuspicious = ubication.suspicious === true;

    if (lat === undefined || lon === undefined || !id) return;

    // 🚀 Lógica de interpolación de CSS
    const cssClass = isSuspicious ? 'car-marker-alert' : 'car-marker';
    const customIcon = L.divIcon({ className: cssClass, iconSize: [16, 16] });

    if (corredores[id]) {
        corredores[id].marker.setLatLng([lat, lon]);
        if (corredores[id].suspicious !== isSuspicious) {
            corredores[id].marker.setIcon(customIcon);
        }
        corredores[id].speed = speed;
        corredores[id].suspicious = isSuspicious;
    } else {
        const nuevoMarcador = L.marker([lat, lon], { icon: customIcon }).addTo(map);
        corredores[id] = { marker: nuevoMarcador, speed: speed, suspicious: isSuspicious };
    }

    const alertTag = isSuspicious ? " <span style='color:red;'>🚨 [ANOMALÍA]</span>" : "";
    corredores[id].marker.bindPopup(`
        <div style="font-family: monospace;">
            <strong>🏎️ ${id}</strong>${alertTag}<br>
            Velocidad: ${speed.toFixed(1)} km/h<br>
            Sala: ${roomCode}
        </div>
    `);

    renderizarListaCorredores();
}

async function refrescarRosterDeSala() {
    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`);
        if (res.ok) aplicarEstadoDeSala(await res.json());
    } catch (e) {
        console.warn('No se pudo refrescar el roster.');
    }
}

function aplicarEstadoDeSala(room) {
    hostNameDeLaSala = room.hostName;
    raceStatusValue.textContent = room.status;
    raceStatusValue.style.color = room.status === 'IN_PROGRESS' ? 'var(--color-teal)' : 'var(--color-amber)';
    ultimoRosterConocido = room.activeRunners || [];
    renderizarListaCorredores();
    btnStartRaceDashboard.style.display = (room.status === 'WAITING') ? 'block' : 'none';
}

btnStartRaceDashboard.addEventListener('click', async () => {
    if (!hostNameDeLaSala) return;
    btnStartRaceDashboard.disabled = true;
    btnStartRaceDashboard.textContent = "⏳ Iniciando...";
    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                hostName: hostNameDeLaSala,
                metaLat: metaCoordenadas[0],
                metaLon: metaCoordenadas[1]
            })
        });
        if (!res.ok) throw new Error();
        log('🏁 Carrera iniciada por el host.', 'info');
    } catch (e) {
        alert('Error de red al iniciar la carrera.');
        btnStartRaceDashboard.disabled = false;
        btnStartRaceDashboard.textContent = "🚀 Iniciar Carrera";
    }
});

function renderizarListaCorredores() {
    activeCountDisplay.innerText = `${ultimoRosterConocido.length} / 6`;
    if (ultimoRosterConocido.length === 0) {
        runnersList.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 0.5rem 0;">Esperando corredores...</div>';
        return;
    }
    runnersList.innerHTML = '';
    ultimoRosterConocido.forEach(id => {
        const transmitiendo = corredores[id] !== undefined;
        const alertColor = (transmitiendo && corredores[id].suspicious) ? 'var(--color-alert)' : (transmitiendo ? 'var(--color-teal)' : 'var(--text-muted)');
        const alertIcon = transmitiendo ? (corredores[id].suspicious ? '🚨' : '🏎️') : '🟡';
        const valueText = transmitiendo ? `${corredores[id].speed.toFixed(1)} km/h` : 'En sala';
        const item = document.createElement('div');
        item.className = 'runner-item';
        item.innerHTML = `<span>${alertIcon} ${id}</span><span style="color: ${alertColor};">${valueText}</span>`;
        runnersList.appendChild(item);
    });
}

document.getElementById('btnCopyRoom').addEventListener('click', () => {
    navigator.clipboard.writeText(roomCode).then(() => alert(`Código copiado: ${roomCode}`));
});
document.getElementById('btnShareLink').addEventListener('click', () => {
    navigator.clipboard.writeText(`${window.location.origin}/lobby.html?room=${encodeURIComponent(roomCode)}`).then(() => alert('🔗 Enlace copiado.'));
});

conectarWebSocket();
refrescarRosterDeSala();
setInterval(refrescarRosterDeSala, 2000);