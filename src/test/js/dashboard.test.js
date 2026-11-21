import { describe, it, expect, afterEach } from 'vitest';
import { abrir } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

const sala = (status, extra = {}) => Object.assign({
    roomCode: 'SALA1', status, hostName: 'Host', hostUser: 'Piloto1', activeRunners: ['Piloto1', 'Rival', 'Lento'],
    metaLat: 4.7828, metaLon: -74.043, radioMetaMetros: 20
}, extra);

const pos = (runnerId, latitude, longitude, extra = {}) => Object.assign({ runnerId, latitude, longitude, speed: 10, car: 'CHEVROLET_SPARK' }, extra);

/** Tablero de la sala SALA1; `estado.valor` cambia lo que responde GET /api/rooms/SALA1. */
function tablero(inicial = sala('WAITING'), o = {}) {
    const estado = { valor: [200, inicial] };
    const pag = abrir('dashboard.html', Object.assign({
        query: '?room=SALA1',
        storage: { ecirace_hostkey_SALA1: 'hk' },
        preparar(api) {
            api.on('GET', '/api/rooms/SALA1', () => estado.valor);
            api.on('GET', '/api/rooms/SALA1/positions', [200, [pos('Lento', 4.7700, -74.043), null]]);
            if (o.rutas) o.rutas(api);
        }
    }, o));
    pag.estado = estado;
    return pag;
}

async function conectado(pag) {
    await pag.esperar();
    const ws = pag.stomp.ultimo;
    ws.abrir();
    return ws;
}

describe('Tablero del host (dashboard.js)', () => {
    it('sin sala válida no arranca', () => {
        p = abrir('dashboard.html', { query: '?room=<script>' });
        expect(p.errorScripts[0].message).toContain('Tablero sin sala');
    });

    it('arma el mapa, confirma el acceso y se conecta con la clave del host', async () => {
        p = tablero(undefined, { gps: { latitude: 4.6, longitude: -74.1 } });
        const ws = await conectado(p);
        expect(p.$('#roomCodeDisplay').textContent).toBe('SALA1');
        expect(p.$('#hostStatusBadge').textContent).toBe('ONLINE');
        expect(ws.subs.map(s => s.destino)).toEqual(['/topic/rooms/SALA1', '/topic/rooms/SALA1/status']);
        expect(ws.subs[0].headers).toEqual({ hostKey: 'hk' });
        expect(ws.subs[0].headers).not.toBe(ws.subs[1].headers);
        expect(p.$('#btnStartRaceDashboard').style.display).toBe('block');
        expect(p.$('#activeCount').textContent).toBe('3 / 6');
        expect(p.$('#btnEncuadre').textContent).toContain('Siguiendo');
    });

    it('sin GPS del navegador marca el campus como central', async () => {
        p = tablero();
        await conectado(p);
        expect(p.errorScripts).toEqual([]);
    });

    it('sin acceso a la sala no abre el socket', async () => {
        p = tablero();
        p.estado.valor = [403, {}];
        await p.cerrar();
        p = abrir('dashboard.html', { query: '?room=SALA1', preparar(api) { api.on('GET', '/api/rooms/SALA1', [403, {}]); } });
        await p.esperar();
        expect(p.stomp.clientes.length).toBe(0);
        expect(p.$('#eventLog').textContent).toContain('Solo el host y los corredores');
        await p.cerrar();
        p = abrir('dashboard.html', { query: '?room=SALA1', preparar(api) { api.on('GET', '/api/rooms/SALA1', [404, {}]); } });
        await p.esperar();
        expect(p.$('#eventLog').textContent).toContain('no existe');
        await p.avanzar(2000);                   // el aviso se muestra una sola vez
        expect(p.$('#eventLog').textContent.match(/no existe/g).length).toBe(1);
    });

    it('mueve la meta solo el host y antes de la largada', async () => {
        let meta = [200, {}];
        p = tablero(undefined, { rutas(api) { api.on('PUT', '/api/rooms/SALA1/meta', () => meta); } });
        await conectado(p);
        const mapa = p.leaflet.mapa;
        await mapa.disparar('click', { latlng: { lat: 4.79, lng: -74.05 } });   // modo apagado: no pasa nada
        expect(p.api.de('PUT', '/api/rooms/SALA1/meta').length).toBe(0);

        p.click('#btnToggleMetaMode');
        expect(p.$('#metaModeStatus').textContent).toContain('ACTIVADO');
        await mapa.disparar('click', { latlng: { lat: 4.79, lng: -74.05 } });
        expect(p.api.de('PUT', '/api/rooms/SALA1/meta')[0].body).toEqual({ lat: 4.79, lon: -74.05 });
        expect(p.$('#eventLog').textContent).toContain('Meta sincronizada');
        meta = [409, { error: 'La carrera ya empezó' }];
        await mapa.disparar('click', { latlng: { lat: 4.791, lng: -74.05 } });
        expect(p.$('#eventLog').textContent).toContain('La carrera ya empezó');
        meta = [409, null];
        await mapa.disparar('click', { latlng: { lat: 4.792, lng: -74.05 } });
        meta = ['network'];
        await mapa.disparar('click', { latlng: { lat: 4.793, lng: -74.05 } });
        p.click('#btnToggleMetaMode');
        expect(p.$('#metaModeStatus').textContent).toBe('DESACTIVADO');

        // con la carrera en curso no se puede mover
        p.stomp.ultimo.emitir('/topic/rooms/SALA1/status', sala('IN_PROGRESS'));
        p.click('#btnToggleMetaMode');
        expect(p.w.document.body.textContent).toContain('con la carrera en curso');
    });

    it('un espectador sin clave de host no puede mover la meta ni iniciar', async () => {
        p = tablero(sala('WAITING', { hostUser: 'Otro' }), { storage: {} });
        await conectado(p);
        p.click('#btnToggleMetaMode');
        expect(p.w.document.body.textContent).toContain('Solo el host');
        expect(p.$('#btnStartRaceDashboard').style.display).toBe('none');
        p.click('#btnStartRaceDashboard');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/start').length).toBe(0);
    });

    it('carrera en vivo: carros en el mapa, anti-trampas y tabla de posiciones', async () => {
        p = tablero();
        const ws = await conectado(p);
        ws.emitir('/topic/rooms/SALA1/status', sala('IN_PROGRESS'));
        expect(p.$('.g-countdown')).not.toBeNull();
        await p.esperar();
        expect(p.api.de('GET', '/api/rooms/SALA1/positions').length).toBe(1);

        ws.emitir('/topic/rooms/SALA1', pos('Piloto1', 4.7800, -74.043, { car: 'mazda_323' }));
        ws.emitir('/topic/rooms/SALA1', pos('Rival', 4.7810, -74.043, { vehicleType: 'RARO', car: null }));
        ws.emitir('/topic/rooms/SALA1', { runnerId: 'Sin coords' });
        p.reloj.frame();
        let filas = p.w.document.querySelectorAll('#lbBody .lb-row');
        expect(filas[0].textContent).toContain('Rival');
        expect(filas[0].className).toContain('lb-leader');
        expect(filas[filas.length - 1].textContent).toContain('Lento');

        // Piloto1 adelanta: aparece la flecha y luego se limpia
        ws.emitir('/topic/rooms/SALA1', pos('Piloto1', 4.7825, -74.043));
        ws.emitir('/topic/rooms/SALA1', pos('Piloto1', 4.7825, -74.043));           // sin moverse: mismo rumbo
        p.reloj.frame();
        expect(p.$('.lb-trend-up')).not.toBeNull();
        expect(p.$('.lb-trend-down')).not.toBeNull();
        p.reloj.advance(4100);
        p.reloj.frame();
        expect(p.$('.lb-trend-up')).toBeNull();

        // en la meta y sospechoso
        ws.emitir('/topic/rooms/SALA1', pos('Piloto1', 4.7828, -74.043));
        ws.emitir('/topic/rooms/SALA1', pos('Rival', 4.7600, -74.043, { suspicious: true, speed: 90 }));
        p.reloj.frame();
        expect(p.$('#eventLog').textContent).toContain('ANTI-CHEAT');
        expect(p.$('#lbBody').textContent).toContain('En meta');
        expect(p.$('.lb-suspicious')).not.toBeNull();
        expect(p.$('#runnersList').textContent).toContain('🚨');
        expect(p.$('#lbBody').textContent).toMatch(/km/);

        // clic en una fila: vuela al carro y deja de encuadrar solo
        p.click('.lb-row');
        expect(p.$('#btnEncuadre').textContent).toContain('Centrar');
        p.click('#btnEncuadre');
        expect(p.$('#btnEncuadre').textContent).toContain('Siguiendo');
        await p.leaflet.mapa.disparar('dragstart');
        await p.leaflet.mapa.disparar('zoomstart');
        ws.emitir('/topic/rooms/SALA1', pos('Piloto1', 4.7829, -74.043));

        // el servidor declara el ganador
        ws.emitir('/topic/rooms/SALA1/status', sala('FINISHED', { winnerId: 'Piloto1', ranked: false }));
        p.reloj.frame();
        const banner = p.$('#victoryBannerDash');
        expect(banner.textContent).toContain('Piloto1');
        expect(banner.textContent).toContain('Carrera de práctica');
        expect(p.$('#lbBody .lb-row').textContent).toContain('GANADOR');
        expect(p.$('#lbFooter').textContent).toContain('clasificación final');
        p.click('#btnCloseVictoryDash');
        expect(banner.style.display).toBe('none');
        ws.emitir('/topic/rooms/SALA1/status', sala('FINISHED', { winnerId: 'Piloto1' }));
        expect(p.w.document.querySelectorAll('#victoryBannerDash').length).toBe(1);
        ws.emitir('/topic/rooms/SALA1/status', sala('WAITING'));
        expect(p.$('#victoryBannerDash')).toBeNull();
    });

    it('ganador sin señal y estados inválidos', async () => {
        p = tablero(sala('IN_PROGRESS', { activeRunners: [] }));
        const ws = await conectado(p);
        p.reloj.frame();
        expect(p.$('#lbBody').textContent).toContain('Esperando corredores');
        expect(p.$('#runnersList').textContent).toContain('Esperando pilotos');
        ws.emitir('/topic/rooms/SALA1/status', { status: 'WAITING' });            // sin roster: se ignora
        ws.emitir('/topic/rooms/SALA1/status', sala('FINISHED', { winnerId: null, activeRunners: [] }));
        expect(p.$('#victoryBannerDash').textContent).toContain('Ninguno');
    });

    it('el host inicia la carrera desde el tablero', async () => {
        let start = [409, { error: 'Se necesitan pilotos' }];
        p = tablero(undefined, { rutas(api) { api.on('POST', '/api/rooms/SALA1/start', () => start); } });
        await conectado(p);
        p.click('#btnStartRaceDashboard');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Se necesitan pilotos');
        expect(p.$('#btnStartRaceDashboard').disabled).toBe(false);
        start = [500, null];
        p.click('#btnStartRaceDashboard');
        await p.esperar();
        start = [200, sala('IN_PROGRESS')];
        p.click('#btnStartRaceDashboard');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/start')[2].body).toEqual({ metaLat: 4.7828, metaLon: -74.043 });
        expect(p.$('#eventLog').textContent).toContain('Carrera iniciada');
    });

    it('el host retira a un piloto antes de la largada', async () => {
        let kick = [200, sala('WAITING', { activeRunners: ['Piloto1'] })];
        p = tablero(undefined, { rutas(api) { api.on('POST', '/api/rooms/SALA1/kick', () => kick); } });
        await conectado(p);
        const botones = p.w.document.querySelectorAll('.roster-kick');
        expect(botones.length).toBe(2);
        p.click(botones[0]);
        p.click('[data-r="0"]');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/kick').length).toBe(0);
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        expect(p.$('#activeCount').textContent).toBe('1 / 6');
        p.stomp.ultimo.emitir('/topic/rooms/SALA1/status', sala('WAITING'));
        kick = [403, { error: 'No eres el host' }];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('No eres el host');
        kick = [403, null];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        kick = ['network'];
        p.click('.roster-kick'); p.click('[data-r="1"]');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Sin conexión con el servidor.');
    });

    it('retirar sin la capa visual usa el confirm del navegador', async () => {
        p = tablero(undefined, { sinGameUI: true, rutas(api) { api.on('POST', '/api/rooms/SALA1/kick', [200, sala('WAITING')]); } });
        await conectado(p);
        p.click('.roster-kick');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/kick').length).toBe(1);
        p.w.confirm = () => false;
        p.click('.roster-kick');
        await p.esperar();
        expect(p.api.de('POST', '/api/rooms/SALA1/kick').length).toBe(1);
    });

    it('copia el código y el enlace de invitación', async () => {
        p = tablero();
        await conectado(p);
        p.click('#btnCopyRoom');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Código copiado: SALA1');
        p.click('#btnShareLink');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Enlace de invitación copiado');
        await p.cerrar();
        p = tablero(undefined, { clipboardFalla: true });
        await conectado(p);
        p.click('#btnCopyRoom');
        await p.esperar();
        expect(p.w.document.body.textContent).toContain('Copia este texto');
        await p.cerrar();
        p = tablero(undefined, { inseguro: true });
        await conectado(p);
        p.click('#btnShareLink');
        expect(p.w.document.body.textContent).toContain('Copia este texto');
    });

    it('la tabla arranca colapsada en pantallas pequeñas y se despliega', async () => {
        p = tablero(undefined, { estrecho: true, ancho: 390 });
        await conectado(p);
        expect(p.$('#leaderboard').classList.contains('collapsed')).toBe(true);
        p.click('#lbToggle');
        expect(p.$('#lbToggle').getAttribute('aria-expanded')).toBe('true');
    });

    it('reintenta el socket si se cae y refresca el roster', async () => {
        p = tablero();
        const ws = await conectado(p);
        ws.fallar();
        expect(p.$('#hostStatusBadge').textContent).toBe('OFFLINE');
        await p.avanzar(5000);
        expect(p.stomp.clientes.length).toBe(2);
        p.estado.valor = ['network'];
        await p.avanzar(2000);
        p.estado.valor = [500, {}];
        await p.avanzar(2000);
        expect(p.errorScripts).toEqual([]);
    });

    it('al recargar con la carrera iniciada toma la meta del servidor', async () => {
        p = tablero(sala('IN_PROGRESS', { metaLat: 4.79, metaLon: -74.05, activeRunners: ['Lento'] }));
        await conectado(p);
        await p.esperar();
        p.reloj.frame();
        expect(p.$('#lbFooter').textContent).toContain('radio 20 m');
        expect(p.leaflet.mapa.encuadres.length).toBeGreaterThan(0);
        p.estado.valor = [200, sala('FINISHED', { metaLat: 4.79, metaLon: -74.05, winnerId: 'Lento', activeRunners: ['Lento'] })];
        await p.avanzar(2000);
        expect(p.leaflet.mapa.vuelos.length).toBeGreaterThan(0);
    });
});
