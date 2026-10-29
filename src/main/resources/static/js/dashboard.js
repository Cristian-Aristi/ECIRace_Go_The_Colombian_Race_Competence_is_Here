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
                headers: {
                    'Content-Type': 'application/json',
                    'ngrok-skip-browser-warning': 'true'
                },
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

// --- Estilos de los marcadores de vehículo en el mapa ---
(function inyectarEstilosCarros() {
    if (document.getElementById('ecirace-car-styles')) return;
    const style = document.createElement('style');
    style.id = 'ecirace-car-styles';
    style.textContent = `
        .vehicle-marker {
            background: transparent !important;
            border: none !important;
            box-shadow: none !important;
        }
        .vehicle-marker .vehicle-label {
            position: absolute;
            left: 50%;
            bottom: 100%;
            transform: translateX(-50%);
            margin-bottom: 2px;
            background: rgba(15, 23, 42, 0.9);
            color: #00f5d4;
            border: 1px solid #00f5d4;
            font-family: monospace;
            font-size: 11px;
            font-weight: bold;
            padding: 2px 6px;
            border-radius: 6px;
            white-space: nowrap;
            box-shadow: 0 2px 6px rgba(0,0,0,0.6);
            pointer-events: none;
        }
        .vehicle-marker .vehicle-label.suspicious {
            background: rgba(220, 38, 38, 0.95);
            color: #fff;
            border-color: #fff;
        }
        .vehicle-marker .vehicle-img {
            display: block;
            width: 100%;
            height: 100%;
            object-fit: contain;
            filter: drop-shadow(0 3px 4px rgba(0,0,0,0.65));
            transition: transform 0.3s linear;
        }
        .vehicle-marker.suspicious .vehicle-img {
            filter: drop-shadow(0 0 6px #dc2626) drop-shadow(0 0 2px #fff);
        }
        .runner-thumb {
            width: 22px;
            height: 22px;
            object-fit: contain;
            vertical-align: middle;
            margin-right: 6px;
        }
    `;
    document.head.appendChild(style);
})();

// --- Catálogo: valor del enum VehicleType (lo que elige el Lobby) -> imagen en /img ---
const VEHICULO_POR_DEFECTO = 'RENAULT_4';
const CATALOGO_VEHICULOS = {
    'RENAULT_4':       '/img/renault.png',
    'CHEVROLET_SPARK': '/img/spark.png',
    'MAZDA_323':       '/img/mazda.png'
};
const TAMANO_ICONO = 48; // px

// Devuelve siempre una clave válida del catálogo (nunca un emoji ni un valor raro)
function resolverVehiculo(ubication) {
    const candidatos = [ubication.car, ubication.vehicleType];
    for (const c of candidatos) {
        const key = String(c || '').trim().toUpperCase();
        if (CATALOGO_VEHICULOS[key]) return key;
    }
    return VEHICULO_POR_DEFECTO;
}

function escaparHtml(txt) {
    return String(txt).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}

// Rumbo (0° = norte) entre dos puntos; las imágenes miran hacia arriba (norte)
function calcularRumbo(lat1, lon1, lat2, lon2) {
    const rad = Math.PI / 180;
    const dLon = (lon2 - lon1) * rad;
    const y = Math.sin(dLon) * Math.cos(lat2 * rad);
    const x = Math.cos(lat1 * rad) * Math.sin(lat2 * rad) -
        Math.sin(lat1 * rad) * Math.cos(lat2 * rad) * Math.cos(dLon);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function crearIconoCarro(id, vehiculoKey, isSuspicious, rumbo) {
    const src = CATALOGO_VEHICULOS[vehiculoKey] || CATALOGO_VEHICULOS[VEHICULO_POR_DEFECTO];
    const etiqueta = isSuspicious ? `🚨 ${escaparHtml(id)}` : escaparHtml(id);
    return L.divIcon({
        className: 'vehicle-marker' + (isSuspicious ? ' suspicious' : ''),
        html: `
            <span class="vehicle-label${isSuspicious ? ' suspicious' : ''}">${etiqueta}</span>
            <img class="vehicle-img" src="${src}" alt="${escaparHtml(id)}"
                 style="transform: rotate(${rumbo || 0}deg);">
        `,
        iconSize: [TAMANO_ICONO, TAMANO_ICONO],
        iconAnchor: [TAMANO_ICONO / 2, TAMANO_ICONO / 2],
        popupAnchor: [0, -TAMANO_ICONO / 2]
    });
}

function log(msg, type = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    eventLog.appendChild(entry);
    eventLog.scrollTop = eventLog.scrollHeight;
}

function conectarWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    const wsUrl = wsProtocol + window.location.host + '/race-ws-native';

    const socket = new WebSocket(wsUrl);
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
            if ((ubication.roomCode || 'CAMPUS-ECI').toUpperCase() === roomCode) {
                actualizarMapaYLista(ubication);
            }
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

// 🚀 3. Actualización del mapa usando el carro de cada corredor
function actualizarMapaYLista(ubication) {
    const id = ubication.runnerId;
    const lat = ubication.latitude;
    const lon = ubication.longitude;
    const speed = (ubication.speed !== undefined) ? ubication.speed : 0.0;
    const isSuspicious = ubication.suspicious === true;
    const vehiculo = resolverVehiculo(ubication);

    if (lat === undefined || lon === undefined || !id) return;

    if (isSuspicious) {
        log(`🚨 [ANTI-CHEAT] ¡Comportamiento sospechoso en ${id}! Velocidad: ${speed.toFixed(1)} km/h`, 'error');
    }

    if (corredores[id]) {
        const c = corredores[id];
        const previo = c.marker.getLatLng();
        // Solo recalculamos el rumbo si el carro se movió lo suficiente (evita saltos por ruido GPS)
        const movio = map.distance(previo, [lat, lon]) > 1.0;
        const rumbo = movio ? calcularRumbo(previo.lat, previo.lng, lat, lon) : c.rumbo;

        c.marker.setLatLng([lat, lon]);
        if (c.suspicious !== isSuspicious || c.car !== vehiculo || c.rumbo !== rumbo) {
            c.marker.setIcon(crearIconoCarro(id, vehiculo, isSuspicious, rumbo));
        }
        c.speed = speed;
        c.suspicious = isSuspicious;
        c.car = vehiculo;
        c.rumbo = rumbo;
    } else {
        const nuevoMarcador = L.marker([lat, lon], {
            icon: crearIconoCarro(id, vehiculo, isSuspicious, 0),
            zIndexOffset: 1000
        }).addTo(map);
        corredores[id] = {
            marker: nuevoMarcador,
            speed: speed,
            suspicious: isSuspicious,
            car: vehiculo,
            rumbo: 0
        };
    }

    const alertTag = isSuspicious ? " <span style='color:red;'>🚨 [ANOMALÍA]</span>" : "";
    corredores[id].marker.bindPopup(`
        <div style="font-family: monospace;">
            <img src="${CATALOGO_VEHICULOS[vehiculo]}" class="runner-thumb" alt="">
            <strong>${escaparHtml(id)}</strong>${alertTag}<br>
            Velocidad: ${speed.toFixed(1)} km/h<br>
            Sala: ${escaparHtml(roomCode)}
        </div>
    `);

    if (!carreraTerminada && raceStatusValue && raceStatusValue.textContent === 'IN_PROGRESS') {
        const distanciaAMeta = map.distance([lat, lon], metaCoordenadas);

        if (distanciaAMeta <= 15.0) {
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
        const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`, {
            headers: {
                'ngrok-skip-browser-warning': 'true',
                'Accept': 'application/json'
            }
        });
        if (res.ok) aplicarEstadoDeSala(await res.json());
    } catch (e) {
        console.warn('No se pudo refrescar el roster.');
    }
}

function aplicarEstadoDeSala(room) {
    hostNameDeLaSala = room.hostName;
    log(`👥 Sala ${roomCode} | Estado: ${room.status}`, 'info');

    if (room.status === 'WAITING' || room.status === 'IN_PROGRESS') {
        carreraTerminada = false;
        const oldBanner = document.getElementById('victoryBannerDash');
        if (oldBanner) oldBanner.remove();
    }

    if (room.status === 'IN_PROGRESS') {
        raceStatusValue.style.color = 'var(--color-teal)';
    } else if (room.status === 'FINISHED') {
        raceStatusValue.style.color = '#FFD700';
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
            headers: {
                'Content-Type': 'application/json',
                'ngrok-skip-browser-warning': 'true'
            },
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

// 🚀 4. Lista lateral mostrando el carro de cada corredor
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
        const icono = transmitiendo
            ? (corredores[id].suspicious
                ? '🚨'
                : `<img class="runner-thumb" src="${CATALOGO_VEHICULOS[corredores[id].car]}" alt="">`)
            : '🟡';
        const valueText = transmitiendo ? `${corredores[id].speed.toFixed(1)} km/h` : 'En sala';
        const item = document.createElement('div');
        item.className = 'runner-item';
        item.innerHTML = `<span>${icono} ${escaparHtml(id)}</span><span style="color: ${alertColor};">${valueText}</span>`;
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