// src/main/resources/static/js/lobby.js

// Capa visual de juego (game-ui.js). Si no cargara, se usan los diálogos del navegador.
const UI = window.GameUI || {
    toast: (m) => alert(m),
    setLabel: (b, t) => { if (b) b.textContent = t; },
    confirm: (m) => Promise.resolve(confirm(m)),
    sfx: { join() {}, error() {}, coin() {}, select() {} },
    settings: { audio: true, highAccuracy: true },
    icon: () => ''
};

const urlParams = new URLSearchParams(window.location.search);
const preselectedRoom = Session.validRoomCode(urlParams.get('room'));

// 🚀 Helpers blindados contra bloqueos de Safari / Modo Incógnito
function safeGetStorage(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
}
function safeSetStorage(key, value) {
    try { localStorage.setItem(key, value); } catch (e) {}
}
function safeRemoveStorage(key) {
    try { localStorage.removeItem(key); } catch (e) {}
}

// La identidad sale SOLO de la sesión emitida por el servidor (ya no se acepta ?player= en la URL)
if (!Session.requireLogin(preselectedRoom)) {
    throw new Error('Sin sesión: redirigiendo al inicio');
}
const activePilot = Session.username();

const pilotNameDisplay = document.getElementById('pilotNameDisplay');
const pilotEmailDisplay = document.getElementById('pilotEmailDisplay');
const racesDisplay = document.getElementById('racesDisplay');
const winsDisplay = document.getElementById('winsDisplay');
const winRateDisplay = document.getElementById('winRateDisplay');
const rankBadge = document.getElementById('rankBadge');
const roomInput = document.getElementById('roomInput');
const activeRoomsContainer = document.getElementById('activeRoomsContainer');
const btnJoinRace = document.getElementById('btnJoinRace');
const joinRoomError = document.getElementById('joinRoomError');
const btnGoDashboard = document.getElementById('btnGoDashboard');
const btnLogout = document.getElementById('btnLogout');

const createRoomSection = document.getElementById('createRoomSection');
const createRoomInput = document.getElementById('createRoomInput');
const btnCreateRoom = document.getElementById('btnCreateRoom');
const createRoomError = document.getElementById('createRoomError');

const waitingRoomSection = document.getElementById('waitingRoomSection');
const currentRoomCodeLabel = document.getElementById('currentRoomCodeLabel');
const raceStatusBadge = document.getElementById('raceStatusBadge');
const rosterCountLabel = document.getElementById('rosterCountLabel');
const capacityBarFill = document.getElementById('capacityBarFill');
const rosterGrid = document.getElementById('rosterGrid');
const hostControls = document.getElementById('hostControls');
const guestWaitingMsg = document.getElementById('guestWaitingMsg');
const btnStartRace = document.getElementById('btnStartRace');
const btnLeaveWaitingRoom = document.getElementById('btnLeaveWaitingRoom');

const MAX_PLAYERS = 6;
let activeRoomCode = null;
let roomPollInterval = null;
let stompClient = null;
let redirecting = false;

// 🚀 Variable en memoria para garantizar que el carro elegido nunca se pierda
let selectedVehicleMemory = safeGetStorage('ecirace_vehicle') || safeGetStorage('ecirace_car') || 'RENAULT_4';

if (preselectedRoom && roomInput) {
    roomInput.value = preselectedRoom.toUpperCase();
}

// Selección de sala (se conecta con addEventListener; el HTML ya no usa onclick)
window.selectRoom = function(code) {
    code = Session.validRoomCode(code);
    if (!code) return;
    roomInput.value = code;
    document.querySelectorAll('.room-pill').forEach(el => {
        el.classList.toggle('active', el.dataset.code === code);
    });
};

// 🚀 Guarda el carro tanto en memoria como en las dos llaves de localStorage
window.selectVehicle = function(vehicleType) {
    selectedVehicleMemory = (vehicleType && vehicleType.trim() !== '') ? vehicleType.trim() : 'RENAULT_4';
    safeSetStorage('ecirace_vehicle', selectedVehicleMemory);
    safeSetStorage('ecirace_car', selectedVehicleMemory);

    document.querySelectorAll('.vehicle-btn').forEach(btn => {
        btn.classList.remove('active');
        btn.setAttribute('aria-pressed', 'false');
    });
    const activeBtn = document.getElementById('btn-' + selectedVehicleMemory);
    if (activeBtn) {
        activeBtn.classList.add('active');
        activeBtn.setAttribute('aria-pressed', 'true');
    }
};

async function cargarSalasActivas() {
    try {
        const res = await Session.api('/api/rooms');
        if (res.ok) {
            const rooms = res.data || [];
            if (rooms && rooms.length > 0) {
                activeRoomsContainer.innerHTML = '';
                rooms.forEach(r => {
                    const isFull = (r.runnersCount || 0) >= MAX_PLAYERS;
                    const isStarted = r.status && r.status !== 'WAITING';
                    const pill = document.createElement('span');
                    pill.className = `room-pill ${r.roomCode === roomInput.value.trim().toUpperCase() ? 'active' : ''} ${isFull ? 'full' : ''} ${isStarted ? 'started' : ''}`;
                    pill.dataset.code = r.roomCode;
                    const ocupados = r.runnersCount || 0;
                    const barras = Math.max(1, Math.ceil((ocupados / MAX_PLAYERS) * 4));
                    const estado = isStarted ? ' · EN CARRERA' : (isFull ? ' · LLENA' : '');
                    pill.innerHTML = `<span class="g-ico" style="width:20px;height:18px;">${UI.icon('wifi')}</span>`
                        + `<strong>${escaparHtml(r.roomCode)}</strong> (${ocupados}/${MAX_PLAYERS})${estado}`
                        + `<span class="g-bars is-on">${[1, 2, 3, 4].map(n => `<i style="${n > barras ? 'background:rgba(0,0,0,.3)' : ''}"></i>`).join('')}</span>`;
                    pill.title = isStarted ? 'La carrera ya empezó' : (isFull ? 'Sala llena' : 'Toca para elegir esta sala');
                    if (!isFull && !isStarted) {
                        pill.addEventListener('click', () => window.selectRoom(r.roomCode));
                    }
                    activeRoomsContainer.appendChild(pill);
                });
            }
            marcarConexion(true);
        } else {
            marcarConexion(false);
        }
    } catch (e) {
        marcarConexion(false);
        console.warn("No se pudieron cargar salas activas del backend:", e);
    }
}

function escaparHtml(txt) {
    return String(txt).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}

// Barras del chip "PILOT HUD": verdes si el servidor responde, rojas si no
function marcarConexion(ok) {
    const bars = document.getElementById('hudBars');
    if (!bars) return;
    bars.classList.remove('is-weak', 'is-on', 'is-off');
    bars.classList.add(ok ? 'is-on' : 'is-off');
    const chip = document.getElementById('hudStatus');
    if (chip) chip.title = ok ? 'Conectado al servidor' : 'Sin conexión con el servidor';
}

async function cargarPerfil() {
    pilotNameDisplay.textContent = activePilot;
    const local = Session.profile();
    if (local) renderizarPerfil(local);
    try {
        const res = await Session.api('/api/auth/me');
        if (res.status === 401) {
            UI.toast('Tu sesión venció. Vuelve a iniciar sesión.', 'warn');
            setTimeout(() => Session.requireLogin(preselectedRoom), 1200);
            return;
        }
        if (res.ok && res.data) {
            Session.updateProfile(res.data);
            renderizarPerfil(res.data);
        }
    } catch (err) {
        marcarConexion(false);
    }
}

function renderizarPerfil(profile) {
    pilotNameDisplay.textContent = profile.username;
    pilotEmailDisplay.textContent = profile.email || 'Piloto invitado';

    const races = profile.racesCount || 0;
    const wins = profile.winsCount || 0;
    racesDisplay.textContent = races;
    winsDisplay.textContent = wins;

    const rate = (races > 0) ? ((wins / races) * 100).toFixed(1) : "0.0";
    winRateDisplay.textContent = `${rate}%`;

    if (races >= 100) {
        rankBadge.textContent = "★★★ (PRO RACER)";
    } else if (races >= 20) {
        rankBadge.textContent = "★★☆ (EXPERIENCED)";
    } else {
        rankBadge.textContent = "★☆☆ (ROOKIE)";
    }

    document.getElementById('myRankName').textContent = profile.username;
    document.getElementById('myRankStats').textContent = `${wins} V / ${races} C · ${rate}%`;
}

btnCreateRoom.addEventListener('click', async () => {
    const desiredCode = createRoomInput.value.trim();
    createRoomError.style.display = 'none';
    if (desiredCode && !Session.validRoomCode(desiredCode)) {
        createRoomError.textContent = 'Código inválido: 3 a 20 caracteres (letras, números, guion o guion bajo).';
        createRoomError.style.display = 'block';
        return;
    }
    btnCreateRoom.disabled = true;
    UI.setLabel(btnCreateRoom, "Creando...");

    try {
        const res = await Session.api('/api/rooms/create', {
            method: 'POST',
            body: { preferredCode: desiredCode || null }
        });

        if (res.status === 201 && res.data && res.data.room) {
            const room = res.data.room;
            Session.setHostKey(room.roomCode, res.data.hostKey);
            // El que crea la sala desde el lobby también corre: se registra como corredor
            const joined = await Session.api(`/api/rooms/${encodeURIComponent(room.roomCode)}/join`, { method: 'POST' });
            if (!joined.ok) console.warn('No se pudo registrar al host como corredor:', joined.status);
            enterWaitingRoom(room.roomCode);
        } else {
            createRoomError.textContent = (res.data && res.data.error) || 'No se pudo crear la sala.';
            createRoomError.style.display = 'block';
            UI.sfx.error();
        }
    } catch (e) {
        createRoomError.textContent = 'Sin conexión con el servidor. Revisa tu red e inténtalo de nuevo.';
        createRoomError.style.display = 'block';
        UI.sfx.error();
    } finally {
        btnCreateRoom.disabled = false;
        UI.setLabel(btnCreateRoom, "Crear");
    }
});

btnJoinRace.addEventListener('click', async () => {
    const room = Session.validRoomCode(roomInput.value || 'CAMPUS-ECI');
    joinRoomError.style.display = 'none';
    if (!room) {
        joinRoomError.textContent = 'Código de sala inválido.';
        joinRoomError.style.display = 'block';
        return;
    }
    btnJoinRace.disabled = true;

    try {
        const res = await Session.api(`/api/rooms/${encodeURIComponent(room)}/join`, { method: 'POST' });

        if (res.ok) {
            enterWaitingRoom(room);
        } else {
            joinRoomError.textContent = (res.data && res.data.error) || `No fue posible unirse a ${room}.`;
            joinRoomError.style.display = 'block';
            UI.sfx.error();
        }
    } catch (e) {
        joinRoomError.textContent = 'Sin conexión con el servidor. Revisa tu red e inténtalo de nuevo.';
        joinRoomError.style.display = 'block';
        UI.sfx.error();
    } finally {
        btnJoinRace.disabled = false;
    }
});

function enterWaitingRoom(roomCode) {
    activeRoomCode = roomCode;
    redirecting = false;
    currentRoomCodeLabel.textContent = roomCode;
    createRoomSection.style.display = 'none';
    waitingRoomSection.style.display = 'block';
    UI.sfx.join();
    try { waitingRoomSection.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {}

    refreshRoomState();
    if (roomPollInterval) clearInterval(roomPollInterval);
    roomPollInterval = setInterval(refreshRoomState, 2000);

    conectarSocketDeSala(roomCode);
}

async function exitWaitingRoom() {
    const saliendo = activeRoomCode;
    activeRoomCode = null;
    // Libera el cupo en el servidor (antes el piloto quedaba ocupando la sala)
    if (saliendo) {
        Session.api(`/api/rooms/${encodeURIComponent(saliendo)}/leave`, { method: 'POST' }).catch(() => {});
    }
    waitingRoomSection.style.display = 'none';
    createRoomSection.style.display = 'block';
    if (roomPollInterval) clearInterval(roomPollInterval);
    if (stompClient) {
        try { stompClient.disconnect(); } catch (e) {}
        stompClient = null;
    }
}

async function refreshRoomState() {
    if (!activeRoomCode || redirecting) return;
    try {
        const res = await Session.api(`/api/rooms/${encodeURIComponent(activeRoomCode)}`, { room: activeRoomCode });
        if (!res.ok) return;
        renderWaitingRoom(res.data);
    } catch (e) {
        console.warn('No se pudo refrescar el estado de la sala:', e);
    }
}

function renderWaitingRoom(room) {
    const pilot = activePilot;
    const soyHost = !!room.hostUser && room.hostUser === pilot;
    const count = room.runnersCount || 0;

    raceStatusBadge.textContent = room.status;
    raceStatusBadge.className = 'g-pill ' + (room.status === 'IN_PROGRESS' ? 'g-pill--teal' : (room.status === 'FINISHED' ? 'g-pill--red' : 'g-pill--gold'));
    rosterCountLabel.textContent = `${count} / ${MAX_PLAYERS}`;

    const pct = Math.min(100, (count / MAX_PLAYERS) * 100);
    capacityBarFill.style.width = `${pct}%`;
    capacityBarFill.classList.toggle('full', count >= MAX_PLAYERS);

    rosterGrid.innerHTML = '';
    const pilotos = room.activeRunners || [];
    for (let i = 0; i < MAX_PLAYERS; i++) {
        const name = pilotos[i];
        const item = document.createElement('div');
        item.dataset.slot = String(i + 1);
        if (!name) {
            item.className = 'roster-item is-empty';
            item.textContent = 'Libre';
        } else {
            const isHostRunner = !!room.hostUser && name === room.hostUser;
            const soyYo = name === pilot;
            item.className = 'roster-item' + (isHostRunner ? ' is-host' : '') + (soyYo ? ' is-me' : '');
            item.textContent = (isHostRunner ? '👑 ' : '') + name + (soyYo ? ' (tú)' : '');
            item.title = isHostRunner ? 'Host de la sala' : 'Piloto';
        }
        rosterGrid.appendChild(item);
    }

    hostControls.style.display = soyHost ? 'block' : 'none';
    guestWaitingMsg.style.display = (!soyHost && room.status === 'WAITING') ? 'block' : 'none';
    btnStartRace.disabled = room.status !== 'WAITING';

    // 🚀 Enviamos también &car= en la URL para que runner.js siempre reciba el vehículo elegido
    if (room.status === 'IN_PROGRESS' && !redirecting) {
        redirecting = true;
        safeSetStorage('ecirace_car', selectedVehicleMemory);
        safeSetStorage('ecirace_vehicle', selectedVehicleMemory);
        window.location.href = `runner.html?room=${encodeURIComponent(room.roomCode)}&car=${encodeURIComponent(selectedVehicleMemory)}`;
    }
}

btnStartRace.addEventListener('click', async () => {
    btnStartRace.disabled = true;
    UI.setLabel(btnStartRace, "INICIANDO...");

    try {
        const res = await Session.api(`/api/rooms/${encodeURIComponent(activeRoomCode)}/start`, {
            method: 'POST',
            room: activeRoomCode,
            body: {}
        });
        if (!res.ok) {
            UI.toast((res.data && res.data.error) || 'No se pudo iniciar la carrera.', 'error');
            btnStartRace.disabled = false;
            UI.setLabel(btnStartRace, "INICIAR CARRERA");
        }
    } catch (e) {
        UI.toast('Sin conexión con el servidor: la carrera no se inició.', 'error');
        btnStartRace.disabled = false;
        UI.setLabel(btnStartRace, "INICIAR CARRERA");
    }
});

// 🚀 Conectado al canal WebSocket nativo para no fallar con Ngrok
function conectarSocketDeSala(roomCode) {
    try {
        const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
        const wsUrl = wsProtocol + window.location.host + '/race-ws-native';
        const socket = new WebSocket(wsUrl);
        stompClient = Stomp.over(socket);
        stompClient.debug = null;
        stompClient.connect(Session.stompHeaders(), () => {
            const key = Session.hostKey(roomCode);
            stompClient.subscribe(`/topic/rooms/${roomCode}/status`, (msg) => {
                const updatedRoom = JSON.parse(msg.body);
                renderWaitingRoom(updatedRoom);
            }, key ? { hostKey: key } : {});
        }, () => {
            console.warn('No se pudo conectar el socket de la sala de espera; se sigue con polling.');
        });
    } catch (e) {
        console.warn('WebSocket/Stomp no disponible, se usa solo polling:', e);
    }
}

btnLeaveWaitingRoom.addEventListener('click', () => {
    exitWaitingRoom();
});

btnGoDashboard.addEventListener('click', () => {
    const room = Session.validRoomCode(activeRoomCode || roomInput.value || 'CAMPUS-ECI') || 'CAMPUS-ECI';
    window.location.href = `dashboard.html?room=${encodeURIComponent(room)}`;
});

btnLogout.addEventListener('click', async () => {
    if (await UI.confirm("¿Cerrar la sesión de este piloto?", "Cerrar sesión", "Seguir aquí")) {
        if (activeRoomCode) await exitWaitingRoom();
        await Session.logout();
        window.location.href = "index.html";
    }
});

const btnChangeNick = document.getElementById('btnChangeNick');
const changeTagModal = document.getElementById('changeTagModal');
const newTagInput = document.getElementById('newTagInput');
const btnSaveTag = document.getElementById('btnSaveTag');
const btnCancelTag = document.getElementById('btnCancelTag');
const btnCloseTagModal = document.getElementById('btnCloseTagModal');

btnChangeNick.addEventListener('click', () => {
    newTagInput.value = pilotNameDisplay.textContent.trim();
    changeTagModal.style.display = "flex";
});

btnSaveTag.addEventListener('click', async () => {
    const nextName = newTagInput.value.trim();
    if (!nextName || nextName === Session.username()) {
        changeTagModal.style.display = "none";
        return;
    }
    if (!Session.validUsername(nextName)) {
        UI.toast('El tag debe tener 3 a 16 caracteres: letras, números, punto, guion o guion bajo.', 'error');
        return;
    }
    try {
        const res = await Session.api('/api/auth/username', { method: 'PUT', body: { newUsername: nextName } });
        if (res.ok && res.data && res.data.token) {
            // El servidor emite un token nuevo: los anteriores dejan de funcionar
            Session.save(res.data);
            window.location.reload();
            return;
        }
        UI.toast((res.data && res.data.error) || 'No se pudo cambiar el tag.', 'error');
    } catch (e) {
        UI.toast('Sin conexión con el servidor: el tag no cambió.', 'error');
    }
});

btnCancelTag.addEventListener('click', () => { changeTagModal.style.display = "none"; });
btnCloseTagModal.addEventListener('click', () => { changeTagModal.style.display = "none"; });

const btnLeaderboard = document.getElementById('btnLeaderboard');
const leaderboardModal = document.getElementById('leaderboardModal');
const btnCloseLeaderboard = document.getElementById('btnCloseLeaderboard');
const btnDoneLeaderboard = document.getElementById('btnDoneLeaderboard');
btnLeaderboard.addEventListener('click', () => {
    leaderboardModal.style.display = "flex";
    cargarClasificacion();
});

// Top de pilotos por victorias (GET /api/auth/leaderboard)
async function cargarClasificacion() {
    const lista = document.getElementById('leaderboardList');
    lista.innerHTML = '<div class="g-board__empty">Cargando pilotos...</div>';
    try {
        const res = await Session.api('/api/auth/leaderboard', { auth: false });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const pilotos = res.data || [];
        if (!pilotos.length) {
            lista.innerHTML = '<div class="g-board__empty">Aún no hay carreras terminadas. ¡La primera victoria puede ser tuya!</div>';
            return;
        }
        const yo = String(activePilot).toLowerCase();
        const medallas = ['🥇', '🥈', '🥉'];
        lista.innerHTML = '';
        pilotos.forEach((p, i) => {
            const carreras = p.racesCount || 0, victorias = p.winsCount || 0;
            const pct = carreras > 0 ? ((victorias / carreras) * 100).toFixed(0) : '0';
            const fila = document.createElement('div');
            fila.className = 'g-board__row' + (i === 0 ? ' is-first' : '') + (String(p.username).toLowerCase() === yo ? ' is-me' : '');
            fila.innerHTML = `<span class="g-board__pos">${medallas[i] || (i + 1)}</span>`
                + `<span class="g-board__name">${escaparHtml(p.username)}</span>`
                + `<span class="g-board__stat">${victorias} V / ${carreras} C · ${pct}%</span>`;
            lista.appendChild(fila);
        });
    } catch (e) {
        lista.innerHTML = '<div class="g-board__empty">No se pudo cargar la clasificación. Revisa la conexión con el servidor.</div>';
    }
}
btnCloseLeaderboard.addEventListener('click', () => { leaderboardModal.style.display = "none"; });
btnDoneLeaderboard.addEventListener('click', () => { leaderboardModal.style.display = "none"; });

const btnSettings = document.getElementById('btnSettings');
const settingsModal = document.getElementById('settingsModal');
const btnCloseSettings = document.getElementById('btnCloseSettings');
const btnSaveSettings = document.getElementById('btnSaveSettings');
const chkHighAccuracy = document.getElementById('chkHighAccuracy');
const chkAudioAlerts = document.getElementById('chkAudioAlerts');
const chkTrackBg = document.getElementById('chkTrackBg');
function abrirAjustes() {
    chkHighAccuracy.checked = UI.settings.highAccuracy;
    chkAudioAlerts.checked = UI.settings.audio;
    if (chkTrackBg) chkTrackBg.checked = UI.settings.trackBg !== false;
    settingsModal.style.display = "flex";
}
btnSettings.addEventListener('click', abrirAjustes);
const btnSettingsTop = document.getElementById('btnSettingsTop');
if (btnSettingsTop) btnSettingsTop.addEventListener('click', abrirAjustes);
btnCloseSettings.addEventListener('click', () => { settingsModal.style.display = "none"; });
btnSaveSettings.addEventListener('click', () => {
    UI.settings.highAccuracy = chkHighAccuracy.checked;
    UI.settings.audio = chkAudioAlerts.checked;
    if (chkTrackBg) {
        UI.settings.trackBg = chkTrackBg.checked;
        if (UI.applyBackground) UI.applyBackground();
    }
    settingsModal.style.display = "none";
    UI.toast(UI.settings.audio ? 'Ajustes guardados. Sonido activado.' : 'Ajustes guardados. Sonido desactivado.', 'ok');
    if (UI.settings.audio && UI.sfx.test) UI.sfx.test();
});

// Cerrar cualquier modal tocando fuera de él o con Escape
document.querySelectorAll('.modal-backdrop').forEach(m => {
    m.addEventListener('click', (e) => { if (e.target === m) m.style.display = 'none'; });
});
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') document.querySelectorAll('.modal-backdrop').forEach(m => { m.style.display = 'none'; });
});

// Flechas del carrusel de vehículos
const ORDEN_CARROS = ['RENAULT_4', 'CHEVROLET_SPARK', 'MAZDA_323'];
function moverCarro(paso) {
    const i = Math.max(0, ORDEN_CARROS.indexOf(selectedVehicleMemory));
    const siguiente = ORDEN_CARROS[(i + paso + ORDEN_CARROS.length) % ORDEN_CARROS.length];
    window.selectVehicle(siguiente);
    UI.sfx.select();
}
document.getElementById('btnPrevCar').addEventListener('click', () => moverCarro(-1));
document.getElementById('btnNextCar').addEventListener('click', () => moverCarro(1));

// Botones de vehículo y sala por defecto (sin onclick en el HTML: la CSP no permite JS en línea)
document.querySelectorAll('[data-vehicle]').forEach(btn => {
    btn.addEventListener('click', () => window.selectVehicle(btn.dataset.vehicle));
});
document.querySelectorAll('#activeRoomsContainer .room-pill[data-code]').forEach(pill => {
    pill.addEventListener('click', () => window.selectRoom(pill.dataset.code));
});

cargarPerfil();
cargarSalasActivas();
setInterval(cargarSalasActivas, 5000);

window.selectVehicle(selectedVehicleMemory);

if (preselectedRoom) {
    btnJoinRace.click();
}