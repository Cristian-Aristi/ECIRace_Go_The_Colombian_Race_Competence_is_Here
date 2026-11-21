import { describe, it, expect, afterEach } from 'vitest';
import { abrir } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

describe('Session (session.js)', () => {
    it('guarda y lee la sesión emitida por el servidor', () => {
        p = abrir('index.html', { sesion: false });
        const S = p.w.Session;
        expect(S.isLoggedIn()).toBe(false);
        expect(S.username()).toBeNull();
        S.save(null);
        S.save({ token: 'x' });
        expect(S.token()).toBeNull();
        S.save({ token: 'tok', profile: { username: 'Ana' } });
        expect(S.isLoggedIn()).toBe(true);
        expect(S.username()).toBe('Ana');
        S.updateProfile({ username: 'Ana', winsCount: 2 });
        S.updateProfile(null);
        expect(S.profile().winsCount).toBe(2);
        expect(S.stompHeaders()).toEqual({ Authorization: 'Bearer tok' });
        S.clear();
        expect(S.stompHeaders()).toEqual({});
        expect(S.profile()).toBeNull();
    });

    it('tolera un perfil corrupto y un localStorage bloqueado', () => {
        p = abrir('index.html', { sesion: false });
        const S = p.w.Session;
        p.w.localStorage.setItem('ecirace_profile', '{no es json');
        expect(S.profile()).toBeNull();
        const ls = p.w.localStorage;
        Object.defineProperty(p.w, 'localStorage', { configurable: true, get() { throw new Error('bloqueado'); } });
        expect(S.token()).toBeNull();
        S.save({ token: 't', profile: { username: 'x' } });
        S.clear();
        Object.defineProperty(p.w, 'localStorage', { configurable: true, value: ls });
    });

    it('valida códigos de sala, tags y escapa HTML', () => {
        p = abrir('index.html', { sesion: false });
        const S = p.w.Session;
        expect(S.validRoomCode(' sala-1 ')).toBe('SALA-1');
        expect(S.validRoomCode('<script>')).toBeNull();
        expect(S.validRoomCode(null)).toBeNull();
        expect(S.validUsername('Piloto_1')).toBe(true);
        expect(S.validUsername('ab')).toBe(false);
        expect(S.validUsername(undefined)).toBe(false);
        expect(S.escape(`<a href="x">'&'</a>`)).toBe('&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;');
    });

    it('guarda la clave del host por sala', () => {
        p = abrir('index.html', { sesion: false });
        const S = p.w.Session;
        expect(S.hostKey(null)).toBeNull();
        S.setHostKey('sala1', 'clave');
        S.setHostKey(null, 'x');
        S.setHostKey('sala2', null);
        expect(S.hostKey('SALA1')).toBe('clave');
        expect(S.hostKey('sala2')).toBeNull();
    });

    it('api() agrega token y clave del host, serializa el cuerpo y cierra la sesión con 401', async () => {
        p = abrir('index.html', {
            preparar(api) {
                api.on('POST', '/api/x', [200, { ok: 1 }]);
                api.on('GET', '/api/texto', [200, 'no-json']);
                api.on('GET', '/api/vacio', [204, undefined]);
                api.on('GET', '/api/privado', [401, { error: 'vencida' }]);
            }
        });
        const S = p.w.Session;
        S.setHostKey('SALA1', 'k');
        const r = await S.api('/api/x', { method: 'POST', body: { a: 1 }, room: 'SALA1', headers: { 'X-Extra': '1' } });
        expect(r).toEqual({ ok: true, status: 200, data: { ok: 1 } });
        const llamada = p.api.de('POST', '/api/x')[0];
        expect(llamada.headers.Authorization).toBe('Bearer token-de-prueba');
        expect(llamada.headers['X-Host-Key']).toBe('k');
        expect(llamada.headers['Content-Type']).toBe('application/json');
        expect((await S.api('/api/texto')).data).toBeNull();
        expect((await S.api('/api/vacio')).data).toBeNull();
        await S.api('/api/x', { method: 'POST', body: 'crudo', auth: false, room: 'OTRA' });
        expect(p.api.de('POST', '/api/x')[1].headers.Authorization).toBeUndefined();
        const r401 = await S.api('/api/privado');
        expect(r401.status).toBe(401);
        expect(S.token()).toBeNull();
    });

    it('config() se cachea y usa valores seguros si falla', async () => {
        p = abrir('index.html', { sesion: false, preparar(api) { api.on('GET', '/api/auth/config', ['network']); } });
        expect(await p.w.Session.config()).toEqual({ googleEnabled: false, demoTools: false });
        await p.cerrar();
        p = abrir('index.html', { sesion: false, preparar(api) { api.on('GET', '/api/auth/config', [500, {}]); } });
        expect(await p.w.Session.config()).toEqual({ googleEnabled: false, demoTools: false });
        expect(p.api.de('GET', '/api/auth/config').length).toBe(1);
    });

    it('requireLogin y logout', async () => {
        p = abrir('index.html', { preparar(api) { api.on('POST', '/api/auth/logout', ['network']); } });
        const S = p.w.Session;
        expect(S.requireLogin('SALA1')).toBe(true);
        await S.logout();
        expect(S.isLoggedIn()).toBe(false);
        expect(S.requireLogin('SALA1')).toBe(false);
        expect(S.requireLogin(null)).toBe(false);
    });
});
