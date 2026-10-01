const urlParams = new URLSearchParams(window.location.search);
const roomCode = (urlParams.get('room') || 'CAMPUS-ECI').toUpperCase();
document.getElementById('roomCodeDisplay').innerText = roomCode;

const campusCenter = [4.782800, -74.043000];
const map = L.map('map').setView(campusCenter, 16);

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

let settingMetaMode = false;

const btnToggleMetaMode = document.getElementById('btnToggleMetaMode');
const metaModeStatus = document.getElementById('metaModeStatus');

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

if (btnToggleMetaMode) {
    btnToggleMetaMode.addEventListener('click', () => {
        if (raceStatusValue && raceStatusValue.textContent !== 'WAITING') {
            alert("⚠️ No puedes cambiar la meta una vez que la carrera ha comenzado.");
            return;
        }
        settingMetaMode = !settingMetaMode;
        actualizarBotonMetaUI();
    });
}

map.on('click', async function(e) {
    if (raceStatusValue && raceStatusValue.textContent === 'WAITING' && settingMetaMode) {
        metaCoordenadas = [e.latlng.lat, e.latlng.lng];
        metaLayer.setLatLng(metaCoordenadas);
        L.popup()
            .setLatLng(e.latlng)
            .setContent("<b>🏁 ¡Meta Actualizada!</b><br>Puedes seguir haciendo clic para reubicarla o apagar el botón.")
            .openOn(map);

        try {
            await fetch(`/api/rooms/${encodeURIComponent(roomCode)}/meta`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ lat: metaCoordenadas[0], lon: metaCoordenadas[1] })
            });
            log("Meta sincronizada con el servidor.", "info");
        } catch (err) {
            console.log("Meta actualizada localmente (el backend la tomará al iniciar).");
        }
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
let carreraTerminada = false;

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

    if (isSuspicious) {
        log(`🚨 [ANTI-CHEAT] ¡Comportamiento sospechoso en ${id}! Velocidad: ${speed.toFixed(1)} km/h`, 'error');
    }

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
    if (!carreraTerminada && raceStatusValue && raceStatusValue.textContent === 'IN_PROGRESS') {
        const distanciaAMeta = map.distance([lat, lon], metaCoordenadas);

        if (distanciaAMeta <= 3.0) { // Si toca el círculo dorado (margen de 3 metros)
            log(`🏁 ¡El radar detectó que ${id} tocó la meta! Forzando victoria...`, 'info');

            aplicarEstadoDeSala({
                status: 'FINISHED',
                hostName: hostNameDeLaSala,
                activeRunners: ultimoRosterConocido,
                winnerId: id
            });
        }
    }

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

// 🚀 LÓGICA DE ESTADO COMPLETAMENTE LIMPIA Y REORGANIZADA
// 🚀 LÓGICA DE ESTADO BLINDADA
function aplicarEstadoDeSala(room) {
    hostNameDeLaSala = room.hostName;
    log(`👥 Sala ${roomCode} | Estado: ${room.status}`, 'info');

    // 1. EL TRUCO CLAVE: Resetear variables si la sala vuelve a espera o inicia una nueva carrera
    if (room.status === 'WAITING' || room.status === 'IN_PROGRESS') {
        carreraTerminada = false;
        const oldBanner = document.getElementById('victoryBannerDash');
        if (oldBanner) oldBanner.remove();
    }

    if (room.status === 'IN_PROGRESS') {
        raceStatusValue.style.color = 'var(--color-teal)';
    } else if (room.status === 'FINISHED') {
        raceStatusValue.style.color = '#FFD700'; // Dorado
    } else {
        raceStatusValue.style.color = 'var(--color-amber)';
    }
    raceStatusValue.textContent = room.status;

    if (btnStartRaceDashboard) {
        btnStartRaceDashboard.style.display = (room.status === 'WAITING') ? 'block' : 'none';
    }

    ultimoRosterConocido = room.activeRunners || [];
    renderizarListaCorredores();

    if (room.status === 'FINISHED' && !carreraTerminada) {
        carreraTerminada = true;
        const ganador = room.winnerId || "Ninguno";

        const banner = document.createElement('div');
        banner.id = 'victoryBannerDash';
        banner.style.cssText = `
            position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
            background: linear-gradient(135deg, #FFD700, #FFA500); color: #000;
            padding: 1.2rem 2.5rem; border-radius: 12px; font-size: 1.2rem;
            box-shadow: 0 10px 30px rgba(0,0,0,0.7); font-family: monospace;
            font-weight: bold; text-align: center; z-index: 9999; border: 3px solid #fff;
        `;
        banner.innerHTML = `🏁 ¡CARRERA FINALIZADA! 🏁<br><span style="font-size:1rem;">🏆 GANADOR INDISCUTIBLE: <b>${ganador}</b></span>`;
        document.body.appendChild(banner);

        if (typeof map !== 'undefined' && typeof metaCoordenadas !== 'undefined') {
            map.flyTo(metaCoordenadas, 18, { animate: true, duration: 1.5 });
        }

        setTimeout(() => {
            alert(`🏁 ¡CARRERA FINALIZADA!\n\n🏆 GANADOR INDISCUTIBLE: ${ganador}`);
        }, 1000);
    }
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