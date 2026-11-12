// Capa visual de juego (game-ui.js), con respaldo si no cargara
const UI = window.GameUI || {
    toast: (m) => alert(m),
    setLabel: (b, t) => { if (b) b.textContent = t; },
    countdown: () => Promise.resolve(),
    confetti: () => {},
    sfx: { win() {}, coin() {}, error() {} },
    icon: () => ''
};

const urlParams = new URLSearchParams(window.location.search);
const roomCode = (urlParams.get('room') || 'CAMPUS-ECI').toUpperCase();
document.getElementById('roomCodeDisplay').innerText = roomCode;

const campusCenter = [4.782800, -74.043000];
const map = L.map('map').setView(campusCenter, 16);
map.zoomControl.setPosition('bottomright');   // arriba a la izquierda queda tapado por el panel

let metaCoordenadas = [4.782800, -74.043000];
let metaLayer = null;

metaLayer = L.circle(metaCoordenadas, {
    color: '#FFD700',
    fillColor: '#FFD700',
    fillOpacity: 0.25,
    weight: 3,
    dashArray: '6, 6'
}).addTo(map);

metaLayer.bindPopup("<div style='text-align:center;'><b>🏁 LÍNEA DE LLEGADA</b><br>Activa «Mover la meta» y haz clic en el mapa (solo antes de la largada)</div>");

const metaFlag = L.marker(metaCoordenadas, {
    icon: L.divIcon({
        className: 'g-flag-marker',
        html: `<div style="width:40px;height:40px;filter:drop-shadow(0 3px 0 rgba(0,0,0,.45));">${UI.icon('flag')}</div>`,
        iconSize: [40, 40],
        iconAnchor: [4, 38]
    }),
    interactive: false,
    zIndexOffset: 500
}).addTo(map);
function moverMetaVisual() {
    metaLayer.setLatLng(metaCoordenadas);
    metaFlag.setLatLng(metaCoordenadas);
}

let settingMetaMode = false;

const btnToggleMetaMode = document.getElementById('btnToggleMetaMode');
const metaModeStatus = document.getElementById('metaModeStatus');

function actualizarBotonMetaUI() {
    if (!btnToggleMetaMode || !metaModeStatus) return;

    btnToggleMetaMode.classList.toggle('is-on', settingMetaMode);
    btnToggleMetaMode.setAttribute('aria-pressed', String(settingMetaMode));
    metaModeStatus.textContent = settingMetaMode ? 'ACTIVADO (clic en el mapa)' : 'DESACTIVADO';
    document.getElementById('map').style.cursor = settingMetaMode ? 'crosshair' : '';
}

if (btnToggleMetaMode) {
    btnToggleMetaMode.addEventListener('click', () => {
        if (raceStatusValue && raceStatusValue.textContent !== 'WAITING') {
            UI.toast("La meta no se puede mover con la carrera en curso.", 'warn');
            return;
        }
        settingMetaMode = !settingMetaMode;
        actualizarBotonMetaUI();
    });
}

map.on('click', async function(e) {
    if (raceStatusValue && raceStatusValue.textContent === 'WAITING' && settingMetaMode) {
        metaCoordenadas = [e.latlng.lat, e.latlng.lng];
        moverMetaVisual();
        programarLeaderboard();
        L.popup()
            .setLatLng(e.latlng)
            .setContent("<b>🏁 ¡Meta movida!</b><br>Sigue haciendo clic para reubicarla o apaga el botón.")
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

// --- Encuadre automático ---
// El mapa arrancaba fijo en el campus. Con GPS real el corredor está en su ubicación verdadera
// (a veces a km del campus), así que el marcador SÍ existía pero quedaba fuera de la vista.
let autoEncuadre = true;
let ajusteProgramatico = false;

function encuadrarCorredores(forzar) {
    if (!autoEncuadre && !forzar) return;
    const puntos = Object.values(corredores).map(c => c.marker.getLatLng());
    if (puntos.length === 0) return;
    puntos.push(L.latLng(metaCoordenadas[0], metaCoordenadas[1]));   // incluye la meta
    ajusteProgramatico = true;
    map.fitBounds(L.latLngBounds(puntos), { padding: [80, 80], maxZoom: 18, animate: true });
    setTimeout(() => { ajusteProgramatico = false; }, 800);
}

// Si el host mueve o hace zoom al mapa a mano, dejamos de re-encuadrar solos
map.on('dragstart', () => { autoEncuadre = false; actualizarBotonEncuadre(); });
map.on('zoomstart', () => { if (!ajusteProgramatico) { autoEncuadre = false; actualizarBotonEncuadre(); } });

const controlEncuadre = L.control({ position: 'bottomright' });
controlEncuadre.onAdd = function () {
    const btn = L.DomUtil.create('button', 'g-btn');
    btn.type = 'button';
    btn.id = 'btnEncuadre';
    btn.style.cssText = 'font-size:0.9rem;min-height:40px;';
    L.DomEvent.disableClickPropagation(btn);
    L.DomEvent.on(btn, 'click', () => { autoEncuadre = true; actualizarBotonEncuadre(); encuadrarCorredores(true); });
    return btn;
};
controlEncuadre.addTo(map);
function actualizarBotonEncuadre() {
    const btn = document.getElementById('btnEncuadre');
    if (btn) btn.textContent = autoEncuadre ? '🎯 Siguiendo corredores' : '🎯 Centrar en corredores';
}
actualizarBotonEncuadre();
let stompClient = null;
let hostNameDeLaSala = null;
let ultimoRosterConocido = [];
let carreraTerminada = false;
let ultimoEstadoLogueado = null;

const statusDisplay = document.getElementById('status');
const hostStatusBadge = document.getElementById('hostStatusBadge');
const activeCountDisplay = document.getElementById('activeCount');
const runnersList = document.getElementById('runnersList');
const raceStatusValue = document.getElementById('raceStatusValue');
const btnStartRaceDashboard = document.getElementById('btnStartRaceDashboard');
const eventLog = document.getElementById('eventLog');

// Los estilos de los marcadores de carros están en css/game.css

// --- Catálogo: valor del enum VehicleType (lo que elige el Lobby) -> imagen en /img ---
const VEHICULO_POR_DEFECTO = 'RENAULT_4';
const CATALOGO_VEHICULOS = {
    'RENAULT_4':       '/img/renault.png?v=2',
    'CHEVROLET_SPARK': '/img/spark.png?v=2',
    'MAZDA_323':       '/img/mazda.png?v=2'
};
// En pantallas grandes (proyector / PC) los carros se ven más grandes en el mapa
const TAMANO_ICONO = window.innerWidth >= 1200 ? 64 : 48; // px

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
        hostStatusBadge.className = 'g-pill g-pill--teal';

        stompClient.subscribe('/topic/rooms/' + roomCode, function (mensaje) {
            const ubication = JSON.parse(mensaje.body);
            actualizarMapaYLista(ubication);
        });

        stompClient.subscribe('/topic/rooms/' + roomCode + '/status', function (mensaje) {
            aplicarEstadoDeSala(JSON.parse(mensaje.body));
        });

    }, function () {
        statusDisplay.innerText = "Desconectado. Reintentando...";
        statusDisplay.style.color = "var(--color-alert)";
        hostStatusBadge.innerText = "OFFLINE";
        hostStatusBadge.className = 'g-pill g-pill--red';
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
        log(`🚗 ${id} apareció en el mapa (${lat.toFixed(5)}, ${lon.toFixed(5)})`, 'info');
    }
    encuadrarCorredores(false);

    const alertTag = isSuspicious ? " <span style='color:red;'>🚨 [ANOMALÍA]</span>" : "";
    corredores[id].marker.bindPopup(`
        <div style="font-family: monospace;">
            <img src="${CATALOGO_VEHICULOS[vehiculo]}" class="runner-thumb" alt="">
            <strong>${escaparHtml(id)}</strong>${alertTag}<br>
            Velocidad: ${speed.toFixed(1)} km/h<br>
            Sala: ${escaparHtml(roomCode)}
        </div>
    `);

    // La meta y el ganador los decide el servidor (GameLoopService) y llegan por /status.

    renderizarListaCorredores();
    programarLeaderboard();
}

// Recupera la última posición de cada corredor (por si el tablero se abrió tarde o el corredor está quieto)
async function cargarPosicionesConocidas() {
    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(roomCode)}/positions`, {
            headers: { 'ngrok-skip-browser-warning': 'true', 'Accept': 'application/json' }
        });
        if (!res.ok) return;
        const lista = await res.json();
        lista.forEach(u => { if (u && u.runnerId && !corredores[u.runnerId]) actualizarMapaYLista(u); });
    } catch (e) { /* se reintenta en el siguiente ciclo */ }
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
    estadoCarrera = room.status;
    ganadorId = room.winnerId || null;
    if (room.radioMetaMetros) metaRadioM = room.radioMetaMetros;
    // Con la carrera iniciada la meta oficial es la del servidor (si se recarga el tablero, la local
    // sería la del campus por defecto y la tabla mediría contra un punto equivocado).
    if (room.status !== 'WAITING' && room.metaLat != null && room.metaLon != null) {
        const cambio = Math.abs(room.metaLat - metaCoordenadas[0]) > 1e-9 || Math.abs(room.metaLon - metaCoordenadas[1]) > 1e-9;
        if (cambio) {
            metaCoordenadas = [room.metaLat, room.metaLon];
            moverMetaVisual();
        }
    }
    if (room.status !== ultimoEstadoLogueado) {
        log(`👥 Sala ${roomCode} | Estado: ${room.status}`, 'info');
        if (room.status === 'IN_PROGRESS' && ultimoEstadoLogueado === 'WAITING') UI.countdown();
        ultimoEstadoLogueado = room.status;
    }

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
    programarLeaderboard();
    if (room.status === 'IN_PROGRESS' && ultimoRosterConocido.some(r => !corredores[r])) {
        cargarPosicionesConocidas();
    }

    if (room.status === 'FINISHED' && !carreraTerminada) {
        carreraTerminada = true;
        const ganador = room.winnerId || "Ninguno";

        const overlay = document.createElement('div');
        overlay.id = 'victoryBannerDash';
        overlay.className = 'g-result';
        overlay.setAttribute('role', 'alertdialog');
        const ganadorSeguro = escaparHtml(ganador);
        const carroGanador = corredores[ganador] ? CATALOGO_VEHICULOS[corredores[ganador].car] : null;
        overlay.innerHTML = `
            <div class="g-result__card g-panel">
                <div class="g-result__trophy">${UI.icon('trophy')}</div>
                <h2 class="g-result__title">🏁 ¡CARRERA FINALIZADA! 🏁</h2>
                <div class="g-result__sub">🏆 GANADOR INDISCUTIBLE: <b>${ganadorSeguro}</b></div>
                ${carroGanador ? `<img src="${carroGanador}" alt="" style="width:90px;height:90px;margin:0 auto;object-fit:contain;image-rendering:pixelated;transform:rotate(90deg);">` : ''}
                <button type="button" class="g-btn g-btn--gold" id="btnCloseVictoryDash">Ver el mapa</button>
            </div>`;
        document.body.appendChild(overlay);
        overlay.querySelector('#btnCloseVictoryDash').addEventListener('click', () => { overlay.style.display = 'none'; });
        UI.sfx.win();
        UI.confetti(4500);

        if (typeof map !== 'undefined' && typeof metaCoordenadas !== 'undefined') {
            map.flyTo(metaCoordenadas, 18, { animate: true, duration: 1.5 });
        }

    }
}

btnStartRaceDashboard.addEventListener('click', async () => {
    if (!hostNameDeLaSala) return;
    btnStartRaceDashboard.disabled = true;
    UI.setLabel(btnStartRaceDashboard, "INICIANDO...");
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
        UI.toast('No se pudo iniciar la carrera: revisa la conexión con el servidor.', 'error');
        btnStartRaceDashboard.disabled = false;
        UI.setLabel(btnStartRaceDashboard, "INICIAR CARRERA");
    }
});

// 🚀 4. Lista lateral mostrando el carro de cada corredor
function renderizarListaCorredores() {
    activeCountDisplay.innerText = `${ultimoRosterConocido.length} / 6`;
    if (ultimoRosterConocido.length === 0) {
        runnersList.innerHTML = '<div style="color: var(--text-dim); text-align: center; padding: 6px 0;">Esperando pilotos... comparte el enlace con «Invitar».</div>';
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

function copiarTexto(texto, aviso) {
    const ok = () => UI.toast(aviso, 'ok');
    const fallo = () => UI.toast(`Copia este texto: ${texto}`, 'warn', 7000);
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(texto).then(ok, fallo);
    } else {
        fallo();
    }
}
document.getElementById('btnCopyRoom').addEventListener('click', () => {
    copiarTexto(roomCode, `Código copiado: ${roomCode}`);
});
document.getElementById('btnShareLink').addEventListener('click', () => {
    copiarTexto(`${window.location.origin}/lobby.html?room=${encodeURIComponent(roomCode)}`, 'Enlace de invitación copiado. ¡Compártelo con los pilotos!');
});

// ===================== 🏆 TABLA DE POSICIONES =====================
// Ranking en vivo: el primero es quien está MÁS CERCA de la meta. Se calcula en el navegador con la última
// posición de cada corredor (la que ya llega por WebSocket) y la meta de la sala. Cuando el servidor declara
// un ganador (estado FINISHED) ese piloto queda primero, aunque ya no sea el más cercano.
let estadoCarrera = 'WAITING';
let ganadorId = null;
let metaRadioM = 15;                 // se actualiza con room.radioMetaMetros
let rankAnterior = {};               // id -> posición del render anterior (para las flechas ▲▼)
let firmaAnterior = '';              // ids con señal del render anterior (si cambia, no hay flechas: no es un adelantamiento)
const cambioRank = {};               // id -> { dir: 'up'|'down', hasta: timestamp }
let lbPendiente = false;

const lbBody = document.getElementById('lbBody');
const lbFooter = document.getElementById('lbFooter');
const lbPanel = document.getElementById('leaderboard');
const lbToggle = document.getElementById('lbToggle');

// En pantallas pequeñas arranca colapsada para no tapar el mapa
if (window.matchMedia && window.matchMedia('(max-width: 760px)').matches) {
    lbPanel.classList.add('collapsed');
    lbToggle.setAttribute('aria-expanded', 'false');
}
lbToggle.addEventListener('click', () => {
    const colapsada = lbPanel.classList.toggle('collapsed');
    lbToggle.setAttribute('aria-expanded', String(!colapsada));
});

function formatearDistancia(m) {
    if (!isFinite(m)) return '--';
    return m >= 1000 ? `${(m / 1000).toFixed(2)} km` : `${Math.round(m)} m`;
}

function calcularClasificacion() {
    const ids = ultimoRosterConocido.length > 0 ? ultimoRosterConocido.slice() : Object.keys(corredores);
    const filas = ids.map(id => {
        const c = corredores[id];
        const distancia = c ? map.distance(c.marker.getLatLng(), metaCoordenadas) : Infinity;
        return { id, c, distancia, conSenal: !!c,
            ganador: estadoCarrera === 'FINISHED' && !!ganadorId && id.toLowerCase() === ganadorId.toLowerCase() };
    });
    // 1º el ganador oficial, luego por distancia a la meta, al final los que aún no transmiten
    filas.sort((a, b) => {
        if (a.ganador !== b.ganador) return a.ganador ? -1 : 1;
        if (a.conSenal !== b.conSenal) return a.conSenal ? -1 : 1;
        if (a.conSenal && a.distancia !== b.distancia) return a.distancia - b.distancia;
        return a.id.localeCompare(b.id);
    });
    return filas;
}

const MEDALLAS = ['🥇', '🥈', '🥉'];

function renderizarLeaderboard() {
    lbPendiente = false;
    const filas = calcularClasificacion();
    if (filas.length === 0) {
        lbBody.innerHTML = '<div class="lb-empty">Esperando corredores...</div>';
        return;
    }

    const ahora = Date.now();
    const nuevoRank = {};
    // Solo hay flechas si el conjunto de corredores con señal es el mismo que en el render anterior;
    // si entró o salió alguien, las posiciones se corren sin que haya habido un adelantamiento.
    const firma = filas.filter(f => f.conSenal).map(f => f.id).sort().join('|');
    const hayAdelantamientos = firma === firmaAnterior;
    firmaAnterior = firma;
    // Referencia para la diferencia "+X m": el corredor que está más cerca de la meta
    const distMin = Math.min(...filas.filter(f => f.conSenal).map(f => f.distancia));
    let proximaLimpieza = 0;

    lbBody.innerHTML = '';
    filas.forEach((f, i) => {
        const pos = i + 1;
        nuevoRank[f.id] = pos;
        const previo = rankAnterior[f.id];
        if (hayAdelantamientos && previo !== undefined && previo !== pos && f.conSenal) {
            cambioRank[f.id] = { dir: pos < previo ? 'up' : 'down', hasta: ahora + 4000 };
        }
        const cambio = cambioRank[f.id];
        let tendencia = '';
        if (cambio && cambio.hasta > ahora) {
            tendencia = cambio.dir === 'up'
                ? '<span class="lb-trend-up" title="Subió de posición">▲</span>'
                : '<span class="lb-trend-down" title="Bajó de posición">▼</span>';
            proximaLimpieza = Math.max(proximaLimpieza, cambio.hasta - ahora);
        }

        const enMeta = f.conSenal && f.distancia <= metaRadioM;
        const sospechoso = f.conSenal && f.c.suspicious;
        const icono = f.conSenal
            ? `<img src="${CATALOGO_VEHICULOS[f.c.car] || CATALOGO_VEHICULOS[VEHICULO_POR_DEFECTO]}" alt="">`
            : '<span>🟡</span>';

        let distHtml;
        if (!f.conSenal) {
            distHtml = '<div class="lb-dist" style="color: var(--text-muted);">Sin señal</div>';
        } else if (f.ganador) {
            distHtml = `<div class="lb-dist lb-finish">🏁 GANADOR<small>${formatearDistancia(f.distancia)}</small></div>`;
        } else if (enMeta) {
            distHtml = '<div class="lb-dist lb-finish">🏁 En meta</div>';
        } else {
            const dif = f.distancia - distMin;
            const gap = dif >= 1 ? `<small>+${formatearDistancia(dif)}</small>` : '';
            distHtml = `<div class="lb-dist">${formatearDistancia(f.distancia)}${gap}</div>`;
        }

        const fila = document.createElement('div');
        fila.className = 'lb-row' + (i === 0 && f.conSenal ? ' lb-leader' : '') + (!f.conSenal ? ' lb-offline' : '') + (sospechoso ? ' lb-suspicious' : '');
        fila.title = f.conSenal ? 'Clic para ver en el mapa' : 'Aún no transmite posición';
        fila.innerHTML = `
            <div class="lb-pos">${f.conSenal && pos <= 3 ? MEDALLAS[pos - 1] : pos}</div>
            <div class="lb-runner">${icono}<span class="lb-name">${sospechoso ? '🚨 ' : ''}${escaparHtml(f.id)}</span>${tendencia}</div>
            ${distHtml}`;
        if (f.conSenal) {
            fila.addEventListener('click', () => {
                autoEncuadre = false;
                actualizarBotonEncuadre();
                map.flyTo(f.c.marker.getLatLng(), Math.max(map.getZoom(), 17), { duration: 0.8 });
                f.c.marker.openPopup();
            });
        }
        lbBody.appendChild(fila);
    });
    rankAnterior = nuevoRank;

    lbFooter.textContent = estadoCarrera === 'FINISHED'
        ? 'Carrera finalizada · clasificación final'
        : `Ordenado por distancia a la meta · radio ${Math.round(metaRadioM)} m`;

    // Quita las flechas ▲▼ cuando vence su tiempo, aunque no lleguen más posiciones
    if (proximaLimpieza > 0) setTimeout(programarLeaderboard, proximaLimpieza + 50);
}

// Agrupa varias actualizaciones seguidas (llegan decenas de paquetes por segundo) en un solo repintado
function programarLeaderboard() {
    if (lbPendiente) return;
    lbPendiente = true;
    requestAnimationFrame(renderizarLeaderboard);
}

conectarWebSocket();
refrescarRosterDeSala();
setInterval(refrescarRosterDeSala, 2000);