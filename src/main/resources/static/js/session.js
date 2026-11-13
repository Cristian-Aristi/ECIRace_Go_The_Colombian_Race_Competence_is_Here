// src/main/resources/static/js/session.js
// Sesión del piloto y llamadas a la API.
// - El token de sesión lo emite el servidor (login con Google verificado o invitado) y viaja como
//   "Authorization: Bearer". El nombre del piloto SIEMPRE lo decide el servidor a partir del token.
// - La clave del host de cada sala (X-Host-Key) se guarda solo en el equipo que creó la sala.
(function () {
    const TOKEN_KEY = 'ecirace_token';
    const PROFILE_KEY = 'ecirace_profile';
    const HOSTKEY_PREFIX = 'ecirace_hostkey_';

    function get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
    function set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* almacenamiento bloqueado */ } }
    function del(k) { try { localStorage.removeItem(k); } catch (e) { /* almacenamiento bloqueado */ } }

    const Session = {
        token() { return get(TOKEN_KEY); },

        profile() {
            try { return JSON.parse(get(PROFILE_KEY) || 'null'); } catch (e) { return null; }
        },

        username() {
            const p = this.profile();
            return p && p.username ? p.username : null;
        },

        isLoggedIn() { return !!this.token() && !!this.username(); },

        /** Guarda la respuesta de /api/auth/google, /api/auth/guest o /api/auth/username. */
        save(resp) {
            if (!resp || !resp.token || !resp.profile) return;
            set(TOKEN_KEY, resp.token);
            set(PROFILE_KEY, JSON.stringify(resp.profile));
            del('ecirace_username'); // llave de la versión anterior (identidad sin verificar)
        },

        updateProfile(profile) { if (profile) set(PROFILE_KEY, JSON.stringify(profile)); },

        clear() {
            del(TOKEN_KEY);
            del(PROFILE_KEY);
            del('ecirace_username');
        },

        hostKey(room) { return room ? get(HOSTKEY_PREFIX + String(room).toUpperCase()) : null; },
        setHostKey(room, key) { if (room && key) set(HOSTKEY_PREFIX + String(room).toUpperCase(), key); },

        /** Código de sala válido (mismas reglas que el servidor) o null. */
        validRoomCode(code) {
            const c = String(code || '').trim().toUpperCase();
            return /^[A-Z0-9_-]{3,20}$/.test(c) ? c : null;
        },

        validUsername(name) { return /^[\p{L}\p{N}_.\-]{3,16}$/u.test(String(name || '')); },

        escape(txt) {
            return String(txt).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
        },

        /**
         * fetch a la API con el token y, si se indica opts.room, la clave del host de esa sala.
         * Devuelve { ok, status, data }. Un 401 con token borra la sesión (venció o se cerró en otro equipo).
         */
        async api(path, opts = {}) {
            const headers = Object.assign({ 'Accept': 'application/json', 'ngrok-skip-browser-warning': 'true' }, opts.headers || {});
            let body = opts.body;
            if (body !== undefined && typeof body !== 'string') {
                headers['Content-Type'] = 'application/json';
                body = JSON.stringify(body);
            }
            const token = this.token();
            if (token && opts.auth !== false) headers['Authorization'] = 'Bearer ' + token;
            if (opts.room) {
                const key = this.hostKey(opts.room);
                if (key) headers['X-Host-Key'] = key;
            }
            const res = await fetch(path, { method: opts.method || 'GET', headers, body });
            let data = null;
            const text = await res.text();
            if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
            if (res.status === 401 && token && opts.auth !== false) this.clear();
            return { ok: res.ok, status: res.status, data };
        },

        /** Cabeceras del CONNECT de STOMP (el servidor valida el token y fija al piloto). */
        stompHeaders() {
            const t = this.token();
            return t ? { Authorization: 'Bearer ' + t } : {};
        },

        /** Lleva al inicio si no hay sesión. Devuelve false si redirige. */
        requireLogin(room) {
            if (this.isLoggedIn()) return true;
            const code = this.validRoomCode(room);
            window.location.href = code ? `index.html?room=${encodeURIComponent(code)}` : 'index.html';
            return false;
        },

        /** Configuración pública del servidor (client ID de Google, herramientas de demo). Se cachea. */
        async config() {
            if (!this._config) {
                this._config = this.api('/api/auth/config', { auth: false })
                    .then(r => (r.ok && r.data) ? r.data : { googleEnabled: false, demoTools: false })
                    .catch(() => ({ googleEnabled: false, demoTools: false }));
            }
            return this._config;
        },

        async logout() {
            try { await this.api('/api/auth/logout', { method: 'POST' }); } catch (e) { /* sin red: igual se borra local */ }
            this.clear();
        }
    };

    window.Session = Session;
})();
