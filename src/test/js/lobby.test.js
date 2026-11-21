import { describe, it, expect, afterEach } from 'vitest';
import { abrir, PILOTO } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

const sala = (extra = {}) => Object.assign({
    roomCode: 'SALA1', status: 'WAITING', hostUser: 'Piloto1', runnersCount: 2, activeRunners: ['Piloto1', 'Rival']
}, extra);

/** Lobby con las rutas básicas; `rutas` permite sobrescribir respuestas. */
function lobby(rutas = {}, o = {}) {
    return abrir('lobby.html', Object.assign({
        preparar(api) {
            api.on('GET', '/api/rooms', rutas.salas || [200, []]);
            api.on('GET', '/api/auth/me', rutas.me || [200, PILOTO]);
            api.on('POST', /\/join$/, rutas.join || [200, sala()]);
            api.on('POST', /\/leave$/, [200, {}]);
            api.on('GET', '/api/rooms/SALA1', rutas.sala || [200, sala()]);
            if (rutas.extra) rutas.extra(api);
        }
    }, o));
}

describe('Lobby (lobby.js)', () => {
    it('sin sesión no carga el lobby', () => {
        p = abrir('lobby.html', { sesion: false });
        expect(p.errorScripts[0].message).toContain('Sin sesión');
    });

    it('muestra el perfil del servidor y el rango', async () => {
        p = lobby({ me: [200, { username: 'Piloto1', racesCount: 120, winsCount: 30 }] });
        await p.esperar();
        expect(p.$('#rankBadge').textContent).toContain('PRO RACER');
        expect(p.$('#winRateDisplay').textContent).toBe('25.0%');
        expect(p.$('#pilotEmailDisplay').textContent).toBe('Piloto invitado');
        await p.cerrar();
        p = lobby({ me: [200, { username: 'Piloto1', racesCount: 25, winsCount: 0 }] });
        await p.esperar();
        expect(p.$('#rankBadge').textContent).toContain('EXPERIENCED');
        await p.cerrar();
        p = lobby({ me: [200, { username: 'Piloto1' }] });
        await p.esperar();
        expect(p.$('#rankBadge').textContent).toContain('ROOKIE');
        expect(p.$('#winRateDisplay').textContent).toBe('0.0%');
    });

    it('si la sesión venció avisa y vuelve al inicio', async () => {
        p = lobby({ me: [401, { error: 'vencida' }] });
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Tu sesión venció');
        await p.avanzar(1300);
        await p.cerrar();
        p = lobby({ me: ['network'], salas: ['network'] });
        await p.esperar();
        expect(p.$('#hudBars').classList.contains('is-off')).toBe(true);
    });

    it('lista las salas abiertas, llenas y en carrera', async () => {
        p = lobby({
            salas: [200, [
                { roomCode: 'ABIERTA', runnersCount: 2, status: 'WAITING' },
                { roomCode: 'LLENA', runnersCount: 6, status: 'WAITING' },
                { roomCode: 'CORRIENDO', status: 'IN_PROGRESS' }
            ]]
        });
        await p.esperar();
        const pills = p.w.document.querySelectorAll('#activeRoomsContainer .room-pill');
        expect(pills.length).toBe(3);
        expect(pills[1].className).toContain('full');
        expect(pills[2].textContent).toContain('EN CARRERA');
        p.click(pills[0]);
        expect(p.$('#roomInput').value).toBe('ABIERTA');
        p.w.selectRoom('<mal>');
        expect(p.$('#hudBars').classList.contains('is-on')).toBe(true);
        await p.avanzar(5000);
        expect(p.api.de('GET', '/api/rooms').length).toBeGreaterThan(1);
    });

    it('sin salas muestra un mensaje y con error del servidor marca desconexión', async () => {
        let salas = [200, null];
        p = lobby({ salas: () => salas });
        await p.esperar();
        expect(p.$('#activeRoomsContainer').textContent).toContain('No hay salas abiertas');
        salas = [500, {}];
        await p.avanzar(5000);
        expect(p.$('#hudBars').classList.contains('is-off')).toBe(true);
    });

    it('elige el vehículo con los botones y el carrusel', async () => {
        p = lobby({}, { storage: { ecirace_car: 'MAZDA_323' } });
        await p.esperar();
        expect(p.$('#btn-MAZDA_323').classList.contains('active')).toBe(true);
        p.click('#btnNextCar');
        expect(p.w.localStorage.getItem('ecirace_vehicle')).toBe('RENAULT_4');
        p.click('#btnPrevCar');
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('MAZDA_323');
        p.click('[data-vehicle="CHEVROLET_SPARK"]');
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('CHEVROLET_SPARK');
        p.w.selectVehicle('  ');
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('RENAULT_4');
    });

    it('valida el código al unirse y muestra los errores', async () => {
        let join = [409, { error: 'La sala está llena' }];
        p = lobby({ join: () => join });
        await p.esperar();
        p.click('#btnJoinRace');
        expect(p.$('#joinRoomError').textContent).toContain('Escribe el código');
        p.escribir('#roomInput', '<x>');
        p.click('#btnJoinRace');
        expect(p.$('#joinRoomError').textContent).toBe('Código de sala inválido.');
        p.escribir('#roomInput', 'SALA1');
        p.click('#btnJoinRace');
        await p.esperar();
        expect(p.$('#joinRoomError').textContent).toBe('La sala está llena');
        join = [409, null];
        p.click('#btnJoinRace');
        await p.esperar();
        expect(p.$('#joinRoomError').textContent).toContain('No fue posible unirse');
        join = ['network'];
        p.click('#btnJoinRace');
        await p.esperar();
        expect(p.$('#joinRoomError').textContent).toContain('Sin conexión');
    });

    it('crea una sala, entra a la sala de espera y el host retira a un piloto', async () => {
        let kick = [200, sala({ runnersCount: 1, activeRunners: ['Piloto1'] })];
        p = lobby({
            extra(api) {
                api.on('POST', '/api/rooms/create', [201, { room: { roomCode: 'SALA1' }, hostKey: 'hk' }]);
                api.on('POST', '/api/rooms/SALA1/join', [500, {}]);
                api.on('POST', '/api/rooms/SALA1/kick', () => kick);
            }
        });
        await p.esperar();
        p.escribir('#createRoomInput', 'sala1');
        p.click('#btnCreateRoom');
        await p.esperar();
        expect(p.w.Session.hostKey('SALA1')).toBe('hk');
        expect(p.$('#waitingRoomSection').style.display).toBe('block');
        expect(p.$('#hostControls').style.display).toBe('block');
        expect(p.$('#rosterCountLabel').textContent).toBe('2 / 6');
        expect(p.w.document.querySelectorAll('.roster-item.is-empty').length).toBe(4);

        // el socket de la sala actualiza el roster
        const cliente = p.stomp.ultimo;
        cliente.abrir();
        expect(cliente.subs[0].headers).toEqual({ hostKey: 'hk' });
        cliente.emitir('/topic/rooms/SALA1/status', sala({ runnersCount: 3, activeRunners: ['Piloto1', 'Rival', 'Otro'] }));
        expect(p.$('#rosterCountLabel').textContent).toBe('3 / 6');

        // retirar: primero cancela, luego confirma
        p.click('.roster-kick');
        p.click('[data-r="0"]');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/kick').length).toBe(0);
        p.click('.roster-kick');
        p.click('[data-r="1"]');
        await p.esperar();
        expect(p.$('#rosterCountLabel').textContent).toBe('1 / 6');

        cliente.emitir('/topic/rooms/SALA1/status', sala());
        kick = [403, { error: 'Solo el host' }];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Solo el host');
        kick = [403, null];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        kick = ['network'];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Sin conexión con el servidor.');

        cliente.fallar();
        p.click('#btnLeaveWaitingRoom');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/leave').length).toBe(1);
        expect(cliente.desconectado).toBe(true);
        expect(p.$('#createRoomSection').style.display).toBe('block');
    });

    it('errores al crear una sala', async () => {
        let crear = [409, { error: 'Ese código ya está en uso' }];
        p = lobby({ extra(api) { api.on('POST', '/api/rooms/create', () => crear); } });
        await p.esperar();
        p.escribir('#createRoomInput', '<x>');
        p.click('#btnCreateRoom');
        expect(p.$('#createRoomError').textContent).toContain('Código inválido');
        p.escribir('#createRoomInput', '');
        p.click('#btnCreateRoom');
        await p.esperar();
        expect(p.$('#createRoomError').textContent).toBe('Ese código ya está en uso');
        crear = [500, null];
        p.click('#btnCreateRoom');
        await p.esperar();
        expect(p.$('#createRoomError').textContent).toBe('No se pudo crear la sala.');
        crear = ['network'];
        p.click('#btnCreateRoom');
        await p.esperar();
        expect(p.$('#createRoomError').textContent).toContain('Sin conexión');
    });

    it('un invitado espera, la sala arranca y lo manda a correr', async () => {
        let estado = [200, sala({ hostUser: 'Rival', activeRunners: ['Rival', 'Piloto1'] })];
        p = lobby({ sala: () => estado }, { query: '?room=sala1' });
        await p.esperar();
        expect(p.$('#roomInput').value).toBe('SALA1');
        expect(p.$('#guestWaitingMsg').style.display).toBe('block');
        expect(p.$('#hostControls').style.display).toBe('none');
        expect(p.w.document.querySelectorAll('.roster-kick').length).toBe(0);

        estado = [500, {}];
        await p.avanzar(2000);
        estado = ['network'];
        await p.avanzar(2000);
        estado = [200, sala({ status: 'FINISHED', hostUser: null })];
        await p.avanzar(2000);
        expect(p.$('#raceStatusBadge').className).toContain('g-pill--red');
        estado = [200, sala({ status: 'IN_PROGRESS', runnersCount: 6 })];
        await p.avanzar(2000);
        expect(p.$('#raceStatusBadge').className).toContain('g-pill--teal');
        expect(p.$('#capacityBarFill').classList.contains('full')).toBe(true);
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('RENAULT_4');
        await p.avanzar(2000);   // ya está redirigiendo: no vuelve a consultar
    });

    it('sale de la sala si lo retiraron o si la sala ya no existe', async () => {
        let estado = [403, {}];
        p = lobby({ sala: () => estado }, { query: '?room=SALA1' });
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Ya no estás en esta sala.');
        expect(p.$('#waitingRoomSection').style.display).toBe('none');
        estado = [404, {}];
        p.click('#btnJoinRace');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('La sala ya no existe.');
    });

    it('el host inicia la carrera (y ve el error si falla)', async () => {
        let start = [409, { error: 'Se necesitan pilotos' }];
        p = lobby({ extra(api) { api.on('POST', '/api/rooms/SALA1/start', () => start); } }, { query: '?room=SALA1' });
        await p.esperar();
        p.click('#btnStartRace');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Se necesitan pilotos');
        expect(p.$('#btnStartRace').disabled).toBe(false);
        start = [409, null];
        p.click('#btnStartRace');
        await p.esperar();
        start = ['network'];
        p.click('#btnStartRace');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('la carrera no se inició');
        start = [200, sala({ status: 'IN_PROGRESS' })];
        p.click('#btnStartRace');
        await p.esperar();
        expect(p.$('#btnStartRace').disabled).toBe(true);
    });

    it('abre el tablero de la sala elegida', async () => {
        p = lobby();
        await p.esperar();
        p.click('#btnGoDashboard');
        expect(p.w.document.body.textContent).toContain('Escribe o elige primero');
        p.escribir('#roomInput', 'SALA1');
        p.click('#btnGoDashboard');
    });

    it('cierra la sesión (saliendo de la sala) solo si se confirma', async () => {
        p = lobby({ extra(api) { api.on('POST', '/api/auth/logout', [204, undefined]); } }, { query: '?room=SALA1' });
        await p.esperar();
        p.click('#btnLogout');
        p.click('[data-r="0"]');
        await p.esperar();
        expect(p.w.Session.isLoggedIn()).toBe(true);
        p.click('#btnLogout');
        p.click('[data-r="1"]');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/leave').length).toBe(1);
        expect(p.w.Session.isLoggedIn()).toBe(false);
    });

    it('cambia el tag del piloto', async () => {
        let cambio = [409, { error: 'Ese tag ya existe' }];
        p = lobby({ extra(api) { api.on('PUT', '/api/auth/username', () => cambio); } });
        await p.esperar();
        p.click('#btnChangeNick');
        expect(p.$('#changeTagModal').style.display).toBe('flex');
        expect(p.$('#newTagInput').value).toBe('Piloto1');
        p.click('#btnSaveTag');                       // mismo nombre: solo cierra
        expect(p.$('#changeTagModal').style.display).toBe('none');
        p.click('#btnChangeNick');
        p.escribir('#newTagInput', 'a b');
        p.click('#btnSaveTag');
        expect(p.w.document.body.textContent).toContain('El tag debe tener');
        p.escribir('#newTagInput', 'NuevoTag');
        p.click('#btnSaveTag');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Ese tag ya existe');
        cambio = [500, null];
        p.click('#btnSaveTag');
        await p.esperar();
        cambio = ['network'];
        p.click('#btnSaveTag');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('el tag no cambió');
        cambio = [200, { token: 'nuevo', profile: { username: 'NuevoTag' } }];
        p.click('#btnSaveTag');
        await p.esperar();
        expect(p.w.Session.username()).toBe('NuevoTag');
        p.click('#btnCancelTag');
        p.click('#btnChangeNick');
        p.click('#btnCloseTagModal');
        expect(p.$('#changeTagModal').style.display).toBe('none');
    });

    it('muestra la clasificación general', async () => {
        let tabla = [200, [
            { username: 'Piloto1', racesCount: 4, winsCount: 2 },
            { username: 'Rival' }, { username: 'C' }, { username: 'D', racesCount: 1, winsCount: 1 }
        ]];
        p = lobby({ extra(api) { api.on('GET', '/api/auth/leaderboard', () => tabla); } });
        await p.esperar();
        p.click('#btnLeaderboard');
        await p.esperar();
        const filas = p.w.document.querySelectorAll('#leaderboardList .g-board__row');
        expect(filas.length).toBe(4);
        expect(filas[0].className).toContain('is-me');
        expect(filas[3].textContent).toContain('4');
        p.click('#btnCloseLeaderboard');
        tabla = [200, []];
        p.click('#btnLeaderboard');
        await p.esperar();
        expect(p.$('#leaderboardList').textContent).toContain('Aún no hay carreras');
        p.click('#btnDoneLeaderboard');
        tabla = [500, {}];
        p.click('#btnLeaderboard');
        await p.esperar();
        expect(p.$('#leaderboardList').textContent).toContain('No se pudo cargar');
    });

    it('guarda los ajustes y cierra los modales con Escape o tocando fuera', async () => {
        p = lobby();
        await p.esperar();
        p.click('#btnSettings');
        expect(p.$('#settingsModal').style.display).toBe('flex');
        p.$('#chkAudioAlerts').checked = false;
        p.$('#chkTrackBg').checked = false;
        p.click('#btnSaveSettings');
        expect(p.w.document.body.textContent).toContain('Sonido desactivado');
        expect(p.w.GameUI.settings.trackBg).toBe(false);
        if (p.$('#btnSettingsTop')) p.click('#btnSettingsTop'); else p.click('#btnSettings');
        p.$('#chkAudioAlerts').checked = true;
        p.click('#btnSaveSettings');
        expect(p.w.document.body.textContent).toContain('Sonido activado');
        p.click('#btnSettings');
        p.click('#btnCloseSettings');
        p.click('#btnSettings');
        p.teclear('body', 'Escape');
        expect(p.$('#settingsModal').style.display).toBe('none');
        p.click('#btnSettings');
        p.click('#settingsModal');
        expect(p.$('#settingsModal').style.display).toBe('none');
        p.teclear('body', 'a');
    });

    it('funciona con un localStorage bloqueado y sin la capa visual', async () => {
        p = lobby({}, { sinGameUI: true });
        await p.esperar();
        const ls = p.w.localStorage;
        Object.defineProperty(p.w, 'localStorage', { configurable: true, get() { throw new Error('bloqueado'); } });
        p.w.selectVehicle('MAZDA_323');
        Object.defineProperty(p.w, 'localStorage', { configurable: true, value: ls });
        p.click('#btnSettings');
        p.click('#btnSaveSettings');
        expect(p.errorScripts).toEqual([]);
    });
});
