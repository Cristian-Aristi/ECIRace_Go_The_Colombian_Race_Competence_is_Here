import { describe, it, expect, afterEach } from 'vitest';
import { abrir } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

const sinSesion = (extra = {}) => Object.assign({ sesion: false }, extra);

function tokenGoogle(payload) {
    const b64 = Buffer.from(JSON.stringify(payload)).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    return `cabecera.${b64}.firma`;
}

describe('Pantalla de inicio (index.js)', () => {
    it('detecta el computador y muestra el centro de control', () => {
        p = abrir('index.html', sinSesion());
        expect(p.$('#deviceText').textContent).toBe('COMPUTADOR (PC)');
        expect(p.$('#desktopView').style.display).toBe('block');
        p.click('#tabMobile');
        expect(p.$('#mobileView').style.display).toBe('block');
        expect(p.$('#tabMobile').getAttribute('aria-selected')).toBe('true');
    });

    it('detecta el celular y el enlace de invitación', async () => {
        p = abrir('index.html', sinSesion({ movil: true }));
        expect(p.$('#deviceText').textContent).toBe('MÓVIL (PILOTO)');
        await p.cerrar();
        p = abrir('index.html', sinSesion({ query: '?room=SALA1' }));
        expect(p.$('#deviceText').textContent).toBe('INVITADO A SALA');
    });

    it('crea la sala y guarda la clave del host', async () => {
        p = abrir('index.html', sinSesion({
            preparar(api) { api.on('POST', '/api/rooms/create', [201, { room: { roomCode: 'MISALA' }, hostKey: 'hk' }]); }
        }));
        p.escribir('#hostRoomCodeInput', 'misala');
        p.teclear('#hostRoomCodeInput', 'Enter');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/create')[0].body).toEqual({ preferredCode: 'misala' });
        expect(p.w.Session.hostKey('MISALA')).toBe('hk');
    });

    it('rechaza códigos inválidos y muestra los errores del servidor', async () => {
        let respuesta = [409, { error: 'La sala ya existe' }];
        p = abrir('index.html', sinSesion({ preparar(api) { api.on('POST', '/api/rooms/create', () => respuesta); } }));
        p.escribir('#hostRoomCodeInput', '<x>');
        p.click('#btnCreateRoomAndGo');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/create').length).toBe(0);
        expect(p.$('.g-toast').textContent).toContain('Código inválido');

        p.escribir('#hostRoomCodeInput', '');
        p.click('#btnCreateRoomAndGo');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('La sala ya existe');
        expect(p.$('#btnCreateRoomAndGo').disabled).toBe(false);

        respuesta = ['network'];
        p.click('#btnCreateRoomAndGo');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Sin conexión');
    });

    it('entra como invitado (con y sin tag) y maneja los errores', async () => {
        let respuesta = [200, { token: 't1', profile: { username: 'Invitado_1' } }];
        p = abrir('index.html', sinSesion({ preparar(api) { api.on('POST', '/api/auth/guest', () => respuesta); } }));
        p.escribir('#guestInput', 'a b');
        p.click('#btnEnterGuest');
        await p.esperar();
        expect(p.api.de('POST', '/api/auth/guest').length).toBe(0);

        p.escribir('#guestInput', 'Rayo');
        p.teclear('#guestInput', 'Enter');
        await p.esperar();
        expect(p.w.Session.username()).toBe('Invitado_1');

        respuesta = [400, { error: 'Tag ocupado' }];
        p.click('#btnEnterGuest');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Tag ocupado');
        respuesta = [400, null];
        p.click('#btnEnterGuest');
        await p.esperar();
        respuesta = ['network'];
        p.click('#btnEnterGuest');
        await p.esperar();
        expect(p.$('#btnEnterGuest').disabled).toBe(false);
    });

    it('muestra la sesión guardada, continúa al lobby y cierra sesión', async () => {
        p = abrir('index.html', { query: '?room=SALA9', preparar(api) { api.on('POST', '/api/auth/logout', [204, undefined]); } });
        expect(p.$('#activeSessionBanner').style.display).toBe('block');
        expect(p.$('#savedPilotName').textContent).toBe('Piloto1');
        p.click('#btnContinueToLobby');
        p.click('#btnLogoutCurrent');
        await p.esperar();
        expect(p.$('#activeSessionBanner').style.display).toBe('none');
        expect(p.w.Session.isLoggedIn()).toBe(false);
    });

    it('sin Google configurado avisa que no está disponible', async () => {
        p = abrir('index.html', sinSesion({ preparar(api) { api.on('GET', '/api/auth/config', [200, { googleEnabled: false }]); } }));
        await p.esperar();
        expect(p.$('#googleUnavailable').style.display).toBe('block');
    });

    it('login con Google: tag nuevo, tag ocupado, éxito y cancelación', async () => {
        const respuestas = [];
        p = abrir('index.html', sinSesion({
            preparar(api) {
                api.on('GET', '/api/auth/config', [200, { googleEnabled: true, googleClientId: 'cid' }]);
                api.on('POST', '/api/auth/google', () => respuestas.shift());
            }
        }));
        await p.esperar();
        const script = p.w.document.head.querySelector('script[src*="accounts.google.com"]');
        expect(script).not.toBeNull();
        let callback;
        p.w.google = { accounts: { id: { initialize(o) { callback = o.callback; }, renderButton() {} } } };
        script.onload();

        callback(null);
        respuestas.push([409, { code: 'USERNAME_REQUIRED' }]);
        callback({ credential: tokenGoogle({ given_name: 'Juliana' }) });
        await p.esperar();
        expect(p.$('#googleTagModal').style.display).toBe('flex');
        expect(p.$('#googleTagInput').value).toBe('Juliana');

        p.escribir('#googleTagInput', 'x');
        p.click('#btnConfirmGoogleTag');
        expect(p.$('#googleTagError').style.display).toBe('block');

        respuestas.push([409, { code: 'USERNAME_TAKEN', error: 'Ese tag ya existe' }]);
        p.escribir('#googleTagInput', 'Juliana');
        p.teclear('#googleTagInput', 'Enter');
        await p.esperar();
        expect(p.$('#googleTagError').textContent).toBe('Ese tag ya existe');

        respuestas.push([200, { token: 'tg', profile: { username: 'Juliana' } }]);
        p.click('#btnConfirmGoogleTag');
        await p.esperar();
        expect(p.w.Session.username()).toBe('Juliana');
        expect(p.$('#googleTagModal').style.display).toBe('none');

        // token ilegible: se sugiere un tag aleatorio
        respuestas.push([409, { code: 'USERNAME_REQUIRED' }]);
        callback({ credential: 'no-es-un-jwt' });
        await p.esperar();
        expect(p.$('#googleTagInput').value).toMatch(/^Piloto_\d{4}$/);
        p.click('#btnCancelGoogleTag');
        expect(p.$('#googleTagModal').style.display).toBe('none');
        p.click('#btnConfirmGoogleTag');   // sin token pendiente no hace nada

        respuestas.push([401, { error: 'Token de Google inválido' }]);
        callback({ credential: tokenGoogle({ name: 'X' }) });
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Token de Google inválido');

        respuestas.push(['network']);
        callback({ credential: tokenGoogle({}) });
        await p.esperar();

        respuestas.push([409, { code: 'USERNAME_REQUIRED' }]);
        callback({ credential: tokenGoogle({ given_name: 'Ana' }) });
        await p.esperar();
        respuestas.push(['network']);
        p.escribir('#googleTagInput', 'AnaMaria');
        p.click('#btnConfirmGoogleTag');
        await p.esperar();
        expect(p.$('#btnConfirmGoogleTag').disabled).toBe(false);

        script.onerror();
        expect(p.$('#googleUnavailable').style.display).toBe('block');
    });

    it('funciona aunque no cargue la capa visual del juego', async () => {
        p = abrir('index.html', sinSesion({ sinGameUI: true }));
        p.escribir('#hostRoomCodeInput', '<x>');
        p.click('#btnCreateRoomAndGo');
        await p.esperar();
        expect(p.errorScripts).toEqual([]);
    });
});
