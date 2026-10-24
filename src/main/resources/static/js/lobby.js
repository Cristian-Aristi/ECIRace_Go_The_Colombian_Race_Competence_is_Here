// src/main/resources/static/js/lobby.js

const urlParams = new URLSearchParams(window.location.search);
const preselectedRoom = urlParams.get('room');

const activePilot = localStorage.getItem('ecirace_username');
if (!activePilot || activePilot.trim() === "") {
    alert("Acceso denegado: Debes iniciar sesión o ingresar tu nombre de piloto primero.");
    const redirectTarget = preselectedRoom
        ? `index.html?room=${encodeURIComponent(preselectedRoom)}`
        : "index.html";
    window.location.href = redirectTarget;
}

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

if (preselectedRoom) {
    roomInput.value = preselectedRoom.toUpperCase();
}

// Expone la función globalmente para el atributo onclick en el HTML
window.selectRoom = function(code) {
    roomInput.value = code;
    document.querySelectorAll('.room-pill').forEach(el => {
        el.classList.toggle('active', el.dataset.code === code);
    });
};

// Expone la función globalmente para el atributo onclick del vehículo
window.selectVehicle = function(vehicleType) {
    localStorage.setItem('ecirace_vehicle', vehicleType);
    document.querySelectorAll('.vehicle-btn').forEach(btn => btn.classList.remove('active'));
    const activeBtn = document.getElementById('btn-' + vehicleType);
    if (activeBtn) {
        activeBtn.classList.add('active');
    }
};

async function cargarSalasActivas() {
    try {
        const res = await fetch('/api/rooms');
        if (res.ok) {
            const rooms = await res.json();
            if (rooms && rooms.length > 0) {
                activeRoomsContainer.innerHTML = '';
                rooms.forEach(r => {
                    const isFull = (r.runnersCount || 0) >= MAX_PLAYERS;
                    const isStarted = r.status && r.status !== 'WAITING';
                    const pill = document.createElement('span');
                    pill.className = `room-pill ${r.roomCode === roomInput.value.trim().toUpperCase() ? 'active' : ''} ${isFull ? 'full' : ''} ${isStarted ? 'started' : ''}`;
                    pill.dataset.code = r.roomCode;
                    const statusIcon = isStarted ? '🔒' : (isFull ? '⛔' : '🏁');
                    pill.innerHTML = `${statusIcon} <strong>${r.roomCode}</strong> (${r.runnersCount || 0}/${MAX_PLAYERS} 🏎️)`;
                    if (!isFull && !isStarted) {
                        pill.onclick = () => window.selectRoom(r.roomCode);
                    }
                    activeRoomsContainer.appendChild(pill);
                });
            }
        }
    } catch (e) {
        console.warn("No se pudieron cargar salas activas del backend:", e);
    }
}

async function cargarPerfil(username) {
    pilotNameDisplay.textContent = username;
    try {
        const res = await fetch(`/api/auth/profile/${encodeURIComponent(username)}`);
        if (res.ok) {
            const profile = await res.json();
            renderizarPerfil(profile);
        } else {
            const initRes = await fetch('/api/auth/guest', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username })
            });
            if (initRes.ok) {
                const profile = await initRes.json();
                renderizarPerfil(profile);
            }
        }
    } catch (err) {
        renderizarPerfil({
            username: username,
            email: username.toLowerCase() + "@ecirace.local",
            racesCount: 0,
            winsCount: 0,
            rating: 0.0
        });
    }
}

function renderizarPerfil(profile) {
    pilotNameDisplay.textContent = profile.username;
    pilotEmailDisplay.textContent = profile.email || (profile.username.toLowerCase() + "@ecirace.local");

    const races = profile.racesCount || 0;
    const wins = profile.winsCount || 0;
    racesDisplay.textContent = races;
    winsDisplay.textContent = wins;

    const rate = (races > 0) ? ((wins / races) * 100).toFixed(1) : "0.0";
    winRateDisplay.textContent = `${rate}%`;

    if (races >= 100) {
        rankBadge.textContent = "★★★★★ (PRO RACER)";
    } else if (races >= 20) {
        rankBadge.textContent = "★★★☆☆ (EXPERIENCED)";
    } else {
        rankBadge.textContent = "★☆☆☆☆ (ROOKIE)";
    }

    document.getElementById('myRankName').textContent = `📍 ${profile.username} (Tú)`;
    document.getElementById('myRankStats').textContent = `${wins} / ${races} (${rate}%)`;
}

btnCreateRoom.addEventListener('click', async () => {
    const desiredCode = createRoomInput.value.trim();
    const pilot = pilotNameDisplay.textContent.trim();
    createRoomError.style.display = 'none';
    btnCreateRoom.disabled = true;
    btnCreateRoom.textContent = "⏳ Creando...";

    try {
        const res = await fetch('/api/rooms/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ hostName: pilot, preferredCode: desiredCode || null })
        });

        if (res.status === 201) {
            const room = await res.json();
            enterWaitingRoom(room.roomCode);
        } else {
            const err = await res.json().catch(() => ({ error: 'No se pudo crear la sala.' }));
            createRoomError.textContent = err.error || 'Ese código ya está en uso, elige otro.';
            createRoomError.style.display = 'block';
        }
    } catch (e) {
        createRoomError.textContent = 'Error de red al crear la sala.';
        createRoomError.style.display = 'block';
    } finally {
        btnCreateRoom.disabled = false;
        btnCreateRoom.textContent = "🆕 Crear";
    }
});

btnJoinRace.addEventListener('click', async () => {
    const room = roomInput.value.trim().toUpperCase() || 'CAMPUS-ECI';
    const pilot = pilotNameDisplay.textContent.trim();
    joinRoomError.style.display = 'none';
    btnJoinRace.disabled = true;

    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(room)}/join`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ runnerId: pilot })
        });

        if (res.ok) {
            enterWaitingRoom(room);
        } else {
            const err = await res.json().catch(() => ({ error: 'No se pudo unir a la sala.' }));
            joinRoomError.textContent = err.error || `No fue posible unirse a ${room}.`;
            joinRoomError.style.display = 'block';
        }
    } catch (e) {
        joinRoomError.textContent = 'Error de red al unirse a la sala.';
        joinRoomError.style.display = 'block';
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

    refreshRoomState();
    if (roomPollInterval) clearInterval(roomPollInterval);
    roomPollInterval = setInterval(refreshRoomState, 2000);

    conectarSocketDeSala(roomCode);
}

function exitWaitingRoom() {
    activeRoomCode = null;
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
        const res = await fetch(`/api/rooms/${encodeURIComponent(activeRoomCode)}`);
        if (!res.ok) return;
        const room = await res.json();
        renderWaitingRoom(room);
    } catch (e) {
        console.warn('No se pudo refrescar el estado de la sala:', e);
    }
}

function renderWaitingRoom(room) {
    const pilot = pilotNameDisplay.textContent.trim();
    const soyHost = room.hostName && room.hostName.trim().toLowerCase() === pilot.toLowerCase();
    const count = room.runnersCount || 0;

    raceStatusBadge.textContent = room.status;
    raceStatusBadge.style.color = room.status === 'IN_PROGRESS' ? 'var(--color-teal)' : 'var(--color-amber)';
    rosterCountLabel.textContent = `${count} / ${MAX_PLAYERS}`;

    const pct = Math.min(100, (count / MAX_PLAYERS) * 100);
    capacityBarFill.style.width = `${pct}%`;
    capacityBarFill.classList.toggle('full', count >= MAX_PLAYERS);

    rosterGrid.innerHTML = '';
    (room.activeRunners || []).forEach(name => {
        const isHostRunner = room.hostName && name.trim().toLowerCase() === room.hostName.trim().toLowerCase();
        const item = document.createElement('div');
        item.className = `roster-item ${isHostRunner ? 'is-host' : ''}`;
        item.innerHTML = `${isHostRunner ? '👑' : '🏎️'} ${name}`;
        rosterGrid.appendChild(item);
    });

    hostControls.style.display = soyHost ? 'block' : 'none';
    guestWaitingMsg.style.display = (!soyHost && room.status === 'WAITING') ? 'block' : 'none';
    btnStartRace.disabled = room.status !== 'WAITING';

    if (room.status === 'IN_PROGRESS' && !redirecting) {
        redirecting = true;
        window.location.href = `runner.html?player=${encodeURIComponent(pilot)}&room=${encodeURIComponent(room.roomCode)}`;
    }
}

btnStartRace.addEventListener('click', async () => {
    const pilot = pilotNameDisplay.textContent.trim();
    btnStartRace.disabled = true;
    btnStartRace.textContent = "⏳ Iniciando...";

    try {
        const res = await fetch(`/api/rooms/${encodeURIComponent(activeRoomCode)}/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ hostName: pilot })
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({ error: 'No se pudo iniciar la carrera.' }));
            alert(err.error || 'No se pudo iniciar la carrera.');
            btnStartRace.disabled = false;
            btnStartRace.textContent = "🚀 INICIAR CARRERA";
        }
    } catch (e) {
        alert('Error de red al iniciar la carrera.');
        btnStartRace.disabled = false;
        btnStartRace.textContent = "🚀 INICIAR CARRERA";
    }
});

function conectarSocketDeSala(roomCode) {
    try {
        const socket = new SockJS('/race-ws');
        stompClient = Stomp.over(socket);
        stompClient.debug = null;
        stompClient.connect({}, () => {
            stompClient.subscribe(`/topic/rooms/${roomCode}/status`, (msg) => {
                const updatedRoom = JSON.parse(msg.body);
                renderWaitingRoom(updatedRoom);
            });
        }, () => {
            console.warn('No se pudo conectar el socket de la sala de espera; se sigue con polling.');
        });
    } catch (e) {
        console.warn('SockJS/Stomp no disponible, se usa solo polling:', e);
    }
}

btnLeaveWaitingRoom.addEventListener('click', () => {
    exitWaitingRoom();
});

btnGoDashboard.addEventListener('click', () => {
    const room = (activeRoomCode || roomInput.value.trim().toUpperCase() || 'CAMPUS-ECI');
    window.location.href = `dashboard.html?room=${encodeURIComponent(room)}`;
});

btnLogout.addEventListener('click', () => {
    if (confirm("¿Deseas cerrar la sesión de piloto?")) {
        localStorage.removeItem('ecirace_username');
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
    const currentName = pilotNameDisplay.textContent.trim();
    const nextName = newTagInput.value.trim();
    if (!nextName) return;

    try {
        const res = await fetch('/api/auth/username', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ currentUsername: currentName, newUsername: nextName })
        });
        if (res.ok) {
            const updated = await res.json();
            localStorage.setItem('ecirace_username', updated.username);
            renderizarPerfil(updated);
        } else {
            localStorage.setItem('ecirace_username', nextName);
            await cargarPerfil(nextName);
        }
    } catch (e) {
        localStorage.setItem('ecirace_username', nextName);
        await cargarPerfil(nextName);
    }

    changeTagModal.style.display = "none";
});

btnCancelTag.addEventListener('click', () => { changeTagModal.style.display = "none"; });
btnCloseTagModal.addEventListener('click', () => { changeTagModal.style.display = "none"; });

const btnLeaderboard = document.getElementById('btnLeaderboard');
const leaderboardModal = document.getElementById('leaderboardModal');
const btnCloseLeaderboard = document.getElementById('btnCloseLeaderboard');
const btnDoneLeaderboard = document.getElementById('btnDoneLeaderboard');
btnLeaderboard.addEventListener('click', () => { leaderboardModal.style.display = "flex"; });
btnCloseLeaderboard.addEventListener('click', () => { leaderboardModal.style.display = "none"; });
btnDoneLeaderboard.addEventListener('click', () => { leaderboardModal.style.display = "none"; });

const btnSettings = document.getElementById('btnSettings');
const settingsModal = document.getElementById('settingsModal');
const btnCloseSettings = document.getElementById('btnCloseSettings');
const btnSaveSettings = document.getElementById('btnSaveSettings');
btnSettings.addEventListener('click', () => { settingsModal.style.display = "flex"; });
btnCloseSettings.addEventListener('click', () => { settingsModal.style.display = "none"; });
btnSaveSettings.addEventListener('click', () => {
    alert("Preferencias guardadas correctamente.");
    settingsModal.style.display = "none";
});

cargarPerfil(activePilot);
cargarSalasActivas();
setInterval(cargarSalasActivas, 5000);

if (preselectedRoom) {
    // Registrar al piloto en la sala (join) antes de entrar a la sala de espera
    btnJoinRace.click();
}

const currentVehicle = localStorage.getItem('ecirace_vehicle') || 'RENAULT_4';
window.selectVehicle(currentVehicle);