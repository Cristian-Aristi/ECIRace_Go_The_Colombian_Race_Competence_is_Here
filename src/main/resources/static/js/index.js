// src/main/resources/static/js/index.js

// Capa visual de juego (game-ui.js), con respaldo si no cargara
const UI = window.GameUI || {
    toast: (m) => alert(m),
    setLabel: (b, t) => { if (b) b.textContent = t; }
};

const inviteUrlParams = new URLSearchParams(window.location.search);
const roomFromInviteLink = Session.validRoomCode(inviteUrlParams.get('room'));

function irALobby(preselectedRoom) {
    window.location.href = preselectedRoom
        ? `lobby.html?room=${encodeURIComponent(preselectedRoom)}`
        : 'lobby.html';
}

function detectDevice() {
    const ua = navigator.userAgent || navigator.vendor || window.opera;
    const isMobileUA = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(ua);
    const isSmallScreen = window.innerWidth <= 768;
    return (isMobileUA || isSmallScreen) ? 'MOBILE' : 'DESKTOP';
}

const detected = detectDevice();
const deviceIcon = document.getElementById('deviceIcon');
const deviceText = document.getElementById('deviceText');
const tabDesktop = document.getElementById('tabDesktop');
const tabMobile = document.getElementById('tabMobile');
const desktopView = document.getElementById('desktopView');
const mobileView = document.getElementById('mobileView');

function switchRole(role) {
    const desktop = role === 'DESKTOP';
    tabDesktop.classList.toggle('active', desktop);
    tabMobile.classList.toggle('active', !desktop);
    tabDesktop.setAttribute('aria-selected', String(desktop));
    tabMobile.setAttribute('aria-selected', String(!desktop));
    desktopView.style.display = desktop ? 'block' : 'none';
    mobileView.style.display = desktop ? 'none' : 'block';
}
// Sin onclick en el HTML (la CSP no permite JavaScript en línea)
document.querySelectorAll('[data-role]').forEach(el => {
    el.addEventListener('click', () => switchRole(el.dataset.role));
});

if (roomFromInviteLink) {
    deviceIcon.textContent = '📱';
    deviceText.textContent = 'INVITADO A SALA';
    switchRole('MOBILE');
} else if (detected === 'DESKTOP') {
    deviceIcon.textContent = '🖥️';
    deviceText.textContent = 'COMPUTADOR (PC)';
    switchRole('DESKTOP');
} else {
    deviceIcon.textContent = '📱';
    deviceText.textContent = 'MÓVIL (PILOTO)';
    switchRole('MOBILE');
}

// ===================== Centro de control (PC) =====================
const hostRoomCodeInput = document.getElementById('hostRoomCodeInput');
const btnCreateRoomAndGo = document.getElementById('btnCreateRoomAndGo');

btnCreateRoomAndGo.addEventListener('click', async () => {
    const preferred = hostRoomCodeInput.value.trim();
    if (preferred && !Session.validRoomCode(preferred)) {
        UI.toast('Código inválido: 3 a 20 caracteres (letras, números, guion o guion bajo).', 'error');
        return;
    }
    btnCreateRoomAndGo.disabled = true;
    UI.setLabel(btnCreateRoomAndGo, "CREANDO...");
    try {
        const res = await Session.api('/api/rooms/create', {
            method: 'POST',
            body: { preferredCode: preferred || null }
        });
        if (res.ok && res.data && res.data.room) {
            // La clave del host solo la recibe este equipo: con ella inicia la carrera y mueve la meta
            Session.setHostKey(res.data.room.roomCode, res.data.hostKey);
            window.location.href = `dashboard.html?room=${encodeURIComponent(res.data.room.roomCode)}`;
            return;
        }
        UI.toast((res.data && res.data.error) || 'No se pudo crear la sala.', 'error');
    } catch (err) {
        UI.toast('Sin conexión con el servidor. Revisa tu red e inténtalo de nuevo.', 'error');
    }
    btnCreateRoomAndGo.disabled = false;
    UI.setLabel(btnCreateRoomAndGo, "CREAR SALA ▷");
});

// ===================== Sesión del piloto =====================
const activeSessionBanner = document.getElementById('activeSessionBanner');
const savedPilotName = document.getElementById('savedPilotName');
const btnContinueToLobby = document.getElementById('btnContinueToLobby');
const btnLogoutCurrent = document.getElementById('btnLogoutCurrent');

const guestInput = document.getElementById('guestInput');
const btnEnterGuest = document.getElementById('btnEnterGuest');

const googleTagModal = document.getElementById('googleTagModal');
const googleTagInput = document.getElementById('googleTagInput');
const googleTagError = document.getElementById('googleTagError');
const btnConfirmGoogleTag = document.getElementById('btnConfirmGoogleTag');
const btnCancelGoogleTag = document.getElementById('btnCancelGoogleTag');

// ID token de Google pendiente de elegir tag (solo la primera vez que se usa esa cuenta)
let pendingGoogleToken = null;

function sugerirTag(nombre) {
    const limpio = String(nombre || '').normalize('NFC').replace(/[^\p{L}\p{N}_.\-]/gu, '').slice(0, 16);
    return limpio.length >= 3 ? limpio : 'Piloto_' + Math.floor(1000 + Math.random() * 9000);
}

/** Lee el nombre del token de Google SOLO para sugerir un tag (el servidor no confía en esto). */
function nombreSugeridoDelToken(idToken) {
    try {
        const base64 = idToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        const json = decodeURIComponent(atob(base64).split('').map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)).join(''));
        const payload = JSON.parse(json);
        return payload.given_name || payload.name || '';
    } catch (e) {
        return '';
    }
}

async function enviarLoginGoogle(idToken, username) {
    const res = await Session.api('/api/auth/google', {
        method: 'POST',
        auth: false,
        body: { idToken, username: username || null }
    });
    if (res.ok && res.data && res.data.token) {
        Session.save(res.data);
        googleTagModal.style.display = 'none';
        irALobby(roomFromInviteLink);
        return;
    }
    const code = res.data && res.data.code;
    if (code === 'USERNAME_REQUIRED') {
        pendingGoogleToken = idToken;
        googleTagInput.value = sugerirTag(nombreSugeridoDelToken(idToken));
        googleTagError.style.display = 'none';
        googleTagModal.style.display = 'flex';
        googleTagInput.focus();
        return;
    }
    if (code === 'USERNAME_TAKEN' || code === 'INVALID_USERNAME') {
        pendingGoogleToken = idToken;
        googleTagError.textContent = res.data.error;
        googleTagError.style.display = 'block';
        googleTagModal.style.display = 'flex';
        return;
    }
    googleTagModal.style.display = 'none';
    pendingGoogleToken = null;
    UI.toast((res.data && res.data.error) || 'No se pudo iniciar sesión con Google.', 'error');
}

// Callback de Google Identity Services: recibe el ID token firmado por Google
function handleCredentialResponse(response) {
    if (!response || !response.credential) return;
    enviarLoginGoogle(response.credential, null).catch(() =>
        UI.toast('Sin conexión con el servidor. Inténtalo de nuevo.', 'error'));
}

btnConfirmGoogleTag.addEventListener('click', async () => {
    const tag = googleTagInput.value.trim();
    if (!Session.validUsername(tag)) {
        googleTagError.textContent = 'El tag debe tener 3 a 16 caracteres: letras, números, punto, guion o guion bajo.';
        googleTagError.style.display = 'block';
        return;
    }
    if (!pendingGoogleToken) return;
    btnConfirmGoogleTag.disabled = true;
    try {
        await enviarLoginGoogle(pendingGoogleToken, tag);
    } catch (e) {
        UI.toast('Sin conexión con el servidor. Inténtalo de nuevo.', 'error');
    } finally {
        btnConfirmGoogleTag.disabled = false;
    }
});

btnCancelGoogleTag.addEventListener('click', () => {
    googleTagModal.style.display = 'none';
    pendingGoogleToken = null;
});

/** Carga el SDK de Google con el client ID que publica el servidor y dibuja el botón oficial. */
async function iniciarGoogle() {
    const cfg = await Session.config();
    const contenedor = document.getElementById('googleSignInButton');
    const aviso = document.getElementById('googleUnavailable');
    if (!cfg.googleEnabled || !cfg.googleClientId) {
        if (aviso) aviso.style.display = 'block';
        return;
    }
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => {
        google.accounts.id.initialize({
            client_id: cfg.googleClientId,
            callback: handleCredentialResponse,
            ux_mode: 'popup',
            auto_select: false,
            cancel_on_tap_outside: true
        });
        google.accounts.id.renderButton(contenedor, {
            type: 'standard', shape: 'rectangular', theme: 'filled_black',
            text: 'signin_with', size: 'large', logo_alignment: 'left'
        });
    };
    script.onerror = () => { if (aviso) aviso.style.display = 'block'; };
    document.head.appendChild(script);
}

// ===================== Invitado =====================
btnEnterGuest.addEventListener('click', async () => {
    const name = guestInput.value.trim();
    if (name && !Session.validUsername(name)) {
        UI.toast('El tag debe tener 3 a 16 caracteres: letras, números, punto, guion o guion bajo (sin espacios).', 'error');
        return;
    }
    btnEnterGuest.disabled = true;
    try {
        const res = await Session.api('/api/auth/guest', { method: 'POST', auth: false, body: { username: name || null } });
        if (res.ok && res.data && res.data.token) {
            Session.save(res.data);
            irALobby(roomFromInviteLink);
            return;
        }
        UI.toast((res.data && res.data.error) || 'No se pudo entrar como invitado.', 'error');
    } catch (err) {
        UI.toast('Sin conexión con el servidor. Revisa tu red e inténtalo de nuevo.', 'error');
    } finally {
        btnEnterGuest.disabled = false;
    }
});

window.addEventListener('DOMContentLoaded', () => {
    if (Session.isLoggedIn()) {
        savedPilotName.textContent = Session.username();
        activeSessionBanner.style.display = "block";
    } else {
        Session.clear(); // restos de la versión anterior (nombre sin token)
    }
});

btnContinueToLobby.addEventListener('click', () => {
    irALobby(roomFromInviteLink);
});

btnLogoutCurrent.addEventListener('click', async () => {
    await Session.logout();
    activeSessionBanner.style.display = "none";
});

// Enter en el campo de tag o de sala dispara el botón correspondiente
guestInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') btnEnterGuest.click(); });
googleTagInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') btnConfirmGoogleTag.click(); });
hostRoomCodeInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') btnCreateRoomAndGo.click(); });

iniciarGoogle();
