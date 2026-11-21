import { describe, it, expect, afterEach } from 'vitest';
import { abrir } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

const META = { metaLat: 4.7828, metaLon: -74.043 };
const sala = (status, extra = {}) => Object.assign({ roomCode: 'SALA1', status }, META, extra);

/** Página del corredor con la sala en el estado indicado (se puede cambiar con `estado.valor`). */
function runner(estadoInicial = sala('WAITING'), o = {}) {
    const estado = { valor: [200, estadoInicial] };
    const pag = abrir('runner.html', Object.assign({
        query: '?room=SALA1',
        preparar(api) {
            api.on('GET', '/api/rooms/SALA1', () => estado.valor);
            api.on('GET', '/api/auth/config', [200, { demoTools: !!o.demo }]);
        }
    }, o));
    pag.estado = estado;
    return pag;
}

function fix(lat, lng, extra = {}) {
    return { coords: Object.assign({ latitude: lat, longitude: lng, accuracy: 5, speed: null }, extra.coords), timestamp: extra.ts || Date.now() };
}

describe('Corredor (runner.js)', () => {
    it('sin sala o sin sesión no arranca', async () => {
        p = abrir('runner.html', {});
        expect(p.errorScripts[0].message).toContain('Corredor sin sala');
        await p.cerrar();
        p = abrir('runner.html', { query: '?room=SALA1', sesion: false });
        expect(p.errorScripts[0].message).toContain('Sin sesión');
    });

    it('muestra el piloto, la sala, el carro elegido y el velocímetro', async () => {
        p = runner(undefined, { query: '?room=SALA1&car=mazda_323' });
        await p.esperar();
        expect(p.$('#runnerIdDisplay').textContent).toBe('Piloto1');
        expect(p.$('#gateRoomCode').textContent).toBe('SALA1');
        expect(p.$('#driverCarImg').getAttribute('src')).toContain('mazda');
        expect(p.$('#g-needle')).not.toBeNull();
        expect(p.$('#raceStateDisplay').textContent).toBe('WAITING');
        expect(p.$('#btnStartGps').disabled).toBe(true);
        // herramientas de demo ocultas
        expect(p.$('#btnToggleSim').style.display).toBe('none');
    });

    it('usa el carro guardado o uno por defecto si es inválido', async () => {
        p = runner(undefined, { storage: { ecirace_car: 'CHEVROLET_SPARK' } });
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('CHEVROLET_SPARK');
        await p.cerrar();
        p = runner(undefined, { query: '?room=SALA1&car=TANQUE' });
        expect(p.w.localStorage.getItem('ecirace_car')).toBe('RENAULT_4');
    });

    it('antes de la largada no deja usar el GPS', async () => {
        p = runner(undefined, { demo: true });
        await p.esperar();
        p.click('#btnStartGps');
        p.click('#btnToggleSim');
        p.click('#btnSimulateWin');
        await p.esperar();
        expect(p.w.document.querySelectorAll('.g-toast').length).toBe(3);
        expect(p.$('#btnToggleSim').style.display).toBe('');
    });

    it('carrera completa con GPS real: cuenta regresiva, telemetría, meta y victoria', async () => {
        p = runner();
        await p.esperar();
        const ws = p.stomp.ultimo;
        ws.abrir();
        await p.esperar();
        expect(p.$('#wsStatusDisplay').textContent).toContain('CONECTADO');

        // el host inicia: llega por el socket
        ws.emitir('/topic/rooms/SALA1/status', sala('IN_PROGRESS'));
        expect(p.$('#raceGateCard').style.display).toBe('none');
        expect(p.$('.g-countdown')).not.toBeNull();
        await p.avanzar(4000);
        // la cuenta regresiva no se repite en la misma sala
        ws.emitir('/topic/rooms/SALA1/status', sala('WAITING'));
        ws.emitir('/topic/rooms/SALA1/status', sala('IN_PROGRESS'));
        expect(p.$('.g-countdown')).toBeNull();

        p.click('#btnStartGps');
        expect(p.gps.watchers.size).toBe(1);
        const [{ ok, err }] = [...p.gps.watchers.values()];
        ok(fix(4.7810, -74.043, { coords: { accuracy: 120 } }));      // muy impreciso: se descarta
        expect(p.$('#eventLog').textContent).toContain('poco preciso');
        ok(fix(4.7810, -74.043, { ts: 1000 }));
        ok(fix(4.7811, -74.043, { ts: 3000 }));                         // velocidad estimada
        ok(fix(4.7812, -74.043, { ts: 3000, coords: { speed: 2 } }));   // velocidad del sensor
        expect(Number(p.$('#speedDisplay').textContent)).toBeCloseTo(7.2, 1);
        expect(p.$('#distMetaDisplay').textContent).toMatch(/m$/);

        p.reloj.advance(300);
        expect(ws.enviados.length).toBe(1);
        expect(ws.enviados[0].body).toMatchObject({ roomCode: 'SALA1', car: 'RENAULT_4' });
        p.reloj.advance(300);                                            // mismo fix: no se reenvía
        expect(ws.enviados.length).toBe(1);

        ok(fix(4.7828, -74.043, { ts: 9000 }));                          // en la meta
        expect(p.$('#hudStatus').textContent).toBe('EN LA META');
        err({ code: 1, message: 'denegado' });
        err({ code: 3, message: 'timeout' });
        expect(p.$('#eventLog').textContent).toContain('permiso denegado');

        // el servidor confirma el ganador
        ws.emitir('/topic/rooms/SALA1/status', sala('FINISHED', { winnerId: 'Piloto1' }));
        expect(p.$('#victoryBanner').textContent).toContain('GANASTE');
        expect(p.gps.watchers.size).toBe(0);
        ws.emitir('/topic/rooms/SALA1/status', sala('FINISHED', { winnerId: 'Piloto1' }));
        expect(p.w.document.querySelectorAll('#victoryBanner').length).toBe(1);
        // una carrera nueva limpia el resultado
        ws.emitir('/topic/rooms/SALA1/status', sala('WAITING'));
        expect(p.$('#victoryBanner')).toBeNull();
    });

    it('pierde la carrera de práctica contra otro piloto', async () => {
        p = runner(sala('IN_PROGRESS'));
        await p.esperar();
        p.estado.valor = [200, sala('FINISHED', { winnerId: '<b>Rival</b>', ranked: false })];
        await p.avanzar(4000);
        const banner = p.$('#victoryBanner');
        expect(banner.className).toContain('g-result--lose');
        expect(banner.innerHTML).toContain('&lt;b&gt;Rival');
        expect(banner.textContent).toContain('Carrera de práctica');
        await p.cerrar();
        p = runner(sala('IN_PROGRESS'));
        await p.esperar();
        p.estado.valor = [200, sala('FINISHED', { winnerId: 'Piloto1', ranked: false })];
        await p.avanzar(4000);
        expect(p.$('#victoryBanner').textContent).toContain('Llegaste primero a la meta.');
        p.estado.valor = [200, sala('FINISHED', { winnerId: null })];
    });

    it('detiene el rastreo y vuelve a STANDBY', async () => {
        p = runner(sala('IN_PROGRESS'), { inseguro: true });
        await p.esperar();
        p.click('#btnStartGps');
        expect(p.$('#eventLog').textContent).toContain('no es HTTPS');
        p.click('#btnStopGps');
        expect(p.$('#hudStatus').textContent).toBe('STANDBY');
        expect(p.gps.watchers.size).toBe(0);
        expect(p.$('#btnStartGps').disabled).toBe(false);
    });

    it('sin GPS en el navegador sugiere el simulador', async () => {
        p = runner(sala('IN_PROGRESS'), { sinGeolocalizacion: true });
        await p.esperar();
        p.click('#btnStartGps');
        expect(p.w.document.body.textContent).toContain('no tiene GPS');
    });

    it('simulador de ruta: va y vuelve por los waypoints enviando telemetría', async () => {
        p = runner(sala('IN_PROGRESS'), { demo: true });
        await p.esperar();
        const ws = p.stomp.ultimo;
        ws.abrir();
        p.click('#btnToggleSim');
        expect(p.$('#btnToggleSim').textContent).toContain('Detener');
        p.reloj.advance(300 * 400);                                      // ida y vuelta completa
        expect(ws.enviados.length).toBeGreaterThan(100);
        expect(p.$('#sourceBadge').textContent).toBe('GPS_SIMULADOR');
        p.click('#btnToggleSim');
        expect(p.$('#hudStatus').textContent).toBe('STANDBY');
        // también se detiene con el botón general
        p.click('#btnToggleSim');
        p.click('#btnStopGps');
        expect(p.$('#btnToggleSim').textContent).toContain('Activar');
        // con el GPS real activo, el simulador lo reemplaza
        p.$('#btnToggleSim').disabled = false;
        p.click('#btnStartGps');
        p.$('#btnToggleSim').disabled = false;
        p.click('#btnToggleSim');
        expect(p.gps.watchers.size).toBe(0);
    });

    it('si el socket falla al enviar deja de transmitir', async () => {
        p = runner(sala('IN_PROGRESS'), { demo: true });
        await p.esperar();
        const ws = p.stomp.ultimo;
        ws.abrir();
        ws.fallarEnvio = true;
        p.click('#btnToggleSim');
        p.reloj.advance(900);
        expect(ws.enviados.length).toBe(0);
    });

    it('"simular victoria" trota hasta la meta de la sala', async () => {
        p = runner(sala('IN_PROGRESS'), { demo: true });
        await p.esperar();
        const ws = p.stomp.ultimo;
        ws.abrir();
        p.click('#btnToggleSim');
        p.click('#btnSimulateWin');
        await p.esperar();
        expect(p.$('#btnSimulateWin').textContent).toContain('Posicionando...');
        await p.avanzar(2000);
        expect(p.$('#btnSimulateWin').textContent).toContain('Trotando');
        await p.avanzar(20000);
        expect(p.$('#btnSimulateWin').textContent).toContain('esperando confirmación');
        const ultimo = ws.enviados[ws.enviados.length - 1].body;
        expect(ultimo.latitude).toBeCloseTo(4.7828, 5);
    });

    it('"simular victoria" usa la meta por defecto si la sala no responde', async () => {
        p = runner(sala('IN_PROGRESS', { metaLat: null }), { demo: true });
        await p.esperar();
        p.click('#btnStartGps');
        p.estado.valor = ['network'];
        p.click('#btnSimulateWin');
        await p.esperar();
        p.estado.valor = [200, sala('IN_PROGRESS', { metaLat: null })];
        await p.avanzar(1000);
        expect(p.gps.watchers.size).toBe(0);
    });

    it('reconecta el socket cuando se cae y maneja los rechazos del servidor', async () => {
        p = runner();
        await p.esperar();
        const primero = p.stomp.ultimo;
        primero.fallar();
        expect(p.$('#wsStatusDisplay').textContent).toBe('RECONECTANDO...');
        primero.fallar();                                                 // no acumula reintentos
        await p.avanzar(3000);
        expect(p.stomp.clientes.length).toBe(2);

        p.stomp.ultimo.fallar('No tienes acceso a esta sala');
        expect(p.$('#wsStatusDisplay').textContent).toBe('SIN ACCESO');
        await p.avanzar(2600);

        p.stomp.ultimo.fallar('Sesión inválida');
        expect(p.w.Session.isLoggedIn()).toBe(false);
    });

    it('polling del estado: errores, 401 y reconexión de la consulta', async () => {
        p = runner();
        await p.esperar();
        p.estado.valor = ['network'];
        await p.avanzar(4000);
        await p.avanzar(4000);
        expect(p.$('#eventLog').textContent.match(/No se pudo consultar/g).length).toBe(1);
        p.estado.valor = [500, {}];
        await p.avanzar(4000);
        expect(p.$('#raceStateDisplay').textContent).toBe('WAITING');
        p.estado.valor = [401, {}];
        await p.avanzar(4000);
        p.estado.valor = [200, null];
        await p.avanzar(4000);
    });

    it('mantiene la pantalla encendida al volver a la pestaña', async () => {
        p = runner(sala('IN_PROGRESS'));
        await p.esperar();
        p.click('#btnStartGps');
        Object.defineProperty(p.w.document, 'visibilityState', { configurable: true, value: 'visible' });
        p.w.document.dispatchEvent(new p.w.Event('visibilitychange'));
        await p.esperar();
        p.click('#btnStopGps');
        p.w.document.dispatchEvent(new p.w.Event('visibilitychange'));
        expect(p.errorScripts).toEqual([]);
    });

    it('funciona sin la capa visual del juego', async () => {
        p = runner(sala('IN_PROGRESS'), { sinGameUI: true });
        await p.esperar();
        p.estado.valor = [200, sala('FINISHED', { winnerId: 'Piloto1' })];
        await p.avanzar(4000);
        expect(p.$('#victoryBanner')).not.toBeNull();
    });
});
