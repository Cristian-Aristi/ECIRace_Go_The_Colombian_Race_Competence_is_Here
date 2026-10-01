// src/main/resources/static/js/index.js

const inviteUrlParams = new URLSearchParams(window.location.search);
const roomFromInviteLink = inviteUrlParams.get('room');

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
const deviceBadge = document.getElementById('deviceBadge');
const deviceIcon = document.getElementById('deviceIcon');
const deviceText = document.getElementById('deviceText');
const tabDesktop = document.getElementById('tabDesktop');
const tabMobile = document.getElementById('tabMobile');
const desktopView = document.getElementById('desktopView');
const mobileView = document.getElementById('mobileView');

function switchRole(role) {
    if (role === 'DESKTOP') {
        tabDesktop.classList.add('active');
        tabMobile.classList.remove('active');
        desktopView.style.display = 'block';
        mobileView.style.display = 'none';
    } else {
        tabMobile.classList.add('active');
        tabDesktop.classList.remove('active');
        desktopView.style.display = 'none';
        mobileView.style.display = 'block';
    }
}

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

const hostRoomCodeInput = document.getElementById('hostRoomCodeInput');
const btnCreateRoomAndGo = document.getElementById('btnCreateRoomAndGo');

btnCreateRoomAndGo.addEventListener('click', async () => {
    const preferred = hostRoomCodeInput.value.trim();
    btnCreateRoomAndGo.disabled = true;
    btnCreateRoomAndGo.textContent = "⏳ Creando Sala...";

    try {
        const res = await fetch('/api/rooms/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                hostName: 'Host_Central',
                preferredCode: preferred || null
            })
        });

        if (res.ok) {
            const room = await res.json();
            window.location.href = `dashboard.html?room=${encodeURIComponent(room.roomCode)}`;
            return;
        } else {
            const err = await res.json().catch(() => ({ error: 'Ese código ya está en uso.' }));
            alert(err.error || 'No se pudo crear la sala.');
            btnCreateRoomAndGo.disabled = false;
            btnCreateRoomAndGo.textContent = "🚀 CREAR SALA Y ABRIR DASHBOARD ▶";
            return;
        }
    } catch (err) {
        console.warn("Error en creación de sala vía API, usando fallback local:", err);
    }

    const fallbackCode = preferred ? preferred.toUpperCase() : ('ECI-' + Math.floor(1000 + Math.random() * 9000));
    window.location.href = `dashboard.html?room=${encodeURIComponent(fallbackCode)}`;
});

const activeSessionBanner = document.getElementById('activeSessionBanner');
const savedPilotName = document.getElementById('savedPilotName');
const btnContinueToLobby = document.getElementById('btnContinueToLobby');
const btnLogoutCurrent = document.getElementById('btnLogoutCurrent');

const guestInput = document.getElementById('guestInput');
const btnEnterGuest = document.getElementById('btnEnterGuest');
const btnQuickDaniel = document.getElementById('btnQuickDaniel');
const btnQuickCristian = document.getElementById('btnQuickCristian');
const btnQuickSantiago = document.getElementById('btnQuickSantiago');
const btnSimulateGoogle = document.getElementById('btnSimulateGoogle');

const googleTagModal = document.getElementById('googleTagModal');
const googleTagInput = document.getElementById('googleTagInput');
const btnConfirmGoogleTag = document.getElementById('btnConfirmGoogleTag');
const btnCancelGoogleTag = document.getElementById('btnCancelGoogleTag');

let pendingGoogleAuth = null;

function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(atob(base64).split('').map(function(c) {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
        return JSON.parse(jsonPayload);
    } catch (e) {
        console.error("Error al decodificar token JWT:", e);
        return null;
    }
}

window.handleCredentialResponse = function(response) {
    const payload = parseJwt(response.credential);
    if (payload) {
        const defaultName = payload.given_name || payload.name || "Piloto";
        pendingGoogleAuth = {
            idToken: response.credential,
            email: payload.email || (defaultName.toLowerCase() + "@gmail.com"),
            fullName: payload.name || defaultName,
            pictureUrl: payload.picture || ""
        };
        googleTagInput.value = defaultName;
        googleTagModal.style.display = "flex";
    }
};

btnConfirmGoogleTag.addEventListener('click', async () => {
    const chosenTag = googleTagInput.value.trim() || "Piloto";
    if (pendingGoogleAuth) {
        pendingGoogleAuth.username = chosenTag;
        try {
            const res = await fetch('/api/auth/google', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(pendingGoogleAuth)
            });
            if (res.ok) {
                const profile = await res.json();
                guardarSesionYRedirigir(profile.username);
                return;
            }
        } catch (err) {
            console.warn("Backend local fallback:", err);
        }
        guardarSesionYRedirigir(chosenTag);
    }
});

btnCancelGoogleTag.addEventListener('click', () => {
    googleTagModal.style.display = "none";
    pendingGoogleAuth = null;
});

btnEnterGuest.addEventListener('click', async () => {
    const name = guestInput.value.trim() || ("Piloto_" + Math.floor(100 + Math.random() * 900));
    await autenticarInvitado(name);
});

btnQuickDaniel.addEventListener('click', () => autenticarInvitado("Daniel"));
btnQuickCristian.addEventListener('click', () => autenticarInvitado("Cristian"));
btnQuickSantiago.addEventListener('click', () => autenticarInvitado("Santiago"));

btnSimulateGoogle.addEventListener('click', () => {
    const email = prompt("Ingresa tu correo institucional o personal (ej. estudiosiajuan@gmail.com):", "estudiosiajuan@gmail.com");
    if (!email || !email.includes('@')) return;
    const baseName = email.split('@')[0];
    const capitalized = baseName.charAt(0).toUpperCase() + baseName.slice(1);

    pendingGoogleAuth = {
        idToken: "simulated-google-token-" + Date.now(),
        email: email,
        fullName: capitalized,
        pictureUrl: ""
    };
    googleTagInput.value = capitalized;
    googleTagModal.style.display = "flex";
});

async function autenticarInvitado(username) {
    try {
        const res = await fetch('/api/auth/guest', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username })
        });
        if (res.ok) {
            const profile = await res.json();
            guardarSesionYRedirigir(profile.username);
            return;
        }
    } catch (err) {
        console.warn("Backend local fallback:", err);
    }
    guardarSesionYRedirigir(username);
}

function guardarSesionYRedirigir(username) {
    localStorage.setItem('ecirace_username', username);
    irALobby(roomFromInviteLink);
}

window.addEventListener('DOMContentLoaded', () => {
    const active = localStorage.getItem('ecirace_username');
    if (active) {
        savedPilotName.textContent = active;
        activeSessionBanner.style.display = "block";
    }
});

btnContinueToLobby.addEventListener('click', () => {
    irALobby(roomFromInviteLink);
});

btnLogoutCurrent.addEventListener('click', () => {
    localStorage.removeItem('ecirace_username');
    activeSessionBanner.style.display = "none";
});