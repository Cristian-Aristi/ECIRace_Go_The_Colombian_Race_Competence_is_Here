// Entorno de pruebas del frontend de ECIRace GO.
//
// Carga una página real de src/main/resources/static en jsdom y ejecuta sus scripts (game-ui, session y
// el de la página) instrumentados con Istanbul, para medir la cobertura que luego lee SonarCloud.
// Simula todo lo que el navegador obtiene de afuera: la API REST, STOMP sobre WebSocket, Leaflet, el GPS,
// el audio, el canvas y el reloj (los temporizadores avanzan solo cuando la prueba lo pide).
import fs from 'node:fs';
import path from 'node:path';
import { JSDOM, VirtualConsole } from 'jsdom';
import { createInstrumenter } from 'istanbul-lib-instrument';

const ROOT = path.resolve(__dirname, '../../..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
const instrumenter = createInstrumenter({ esModules: false, compact: false, produceSourceMap: false });
const instrumentados = new Map();

// Un solo objeto de cobertura para todas las páginas que cargue este archivo de pruebas
globalThis.__coverage__ = globalThis.__coverage__ || {};

function instrumentar(relativo) {
    if (!instrumentados.has(relativo)) {
        const codigo = fs.readFileSync(path.join(ROOT, relativo), 'utf8');
        instrumentados.set(relativo, instrumenter.instrumentSync(codigo, relativo));
    }
    return instrumentados.get(relativo);
}

// ---------------------------------------------------------------- reloj controlado
function crearReloj() {
    let ahora = 0;
    let siguienteId = 1;
    const timers = new Map();
    const frames = new Map();
    return {
        get now() { return ahora; },
        setTimeout(fn, ms) { const id = siguienteId++; timers.set(id, { fn, at: ahora + (ms || 0), every: 0 }); return id; },
        setInterval(fn, ms) { const id = siguienteId++; timers.set(id, { fn, at: ahora + (ms || 0), every: Math.max(1, ms || 0) }); return id; },
        clear(id) { timers.delete(id); },
        raf(fn) { const id = siguienteId++; frames.set(id, fn); return id; },
        cancelRaf(id) { frames.delete(id); },
        /** Ejecuta los cuadros de animación pendientes (como el navegador antes de pintar). */
        frame(t) {
            const pendientes = [...frames.values()];
            frames.clear();
            pendientes.forEach(fn => fn(t === undefined ? ahora : t));
        },
        /** Avanza el reloj y dispara, en orden, los temporizadores que vencen. */
        advance(ms) {
            const fin = ahora + ms;
            for (;;) {
                let proximo = null;
                for (const [id, t] of timers) if (t.at <= fin && (!proximo || t.at < proximo[1].at)) proximo = [id, t];
                if (!proximo) break;
                const [id, t] = proximo;
                ahora = Math.max(ahora, t.at);
                if (t.every) t.at += t.every; else timers.delete(id);
                t.fn();
            }
            ahora = fin;
        },
        pendientes() { return timers.size; }
    };
}

// ---------------------------------------------------------------- API REST simulada
function crearApi() {
    const rutas = [];
    const llamadas = [];
    return {
        llamadas,
        /** on('POST', '/api/rooms/create', (req) => [201, {...}]) o un valor fijo [status, body]. */
        on(method, ruta, respuesta) { rutas.unshift({ method, ruta, respuesta }); },
        reset() { rutas.length = 0; llamadas.length = 0; },
        async fetch(url, opts = {}) {
            const method = (opts.method || 'GET').toUpperCase();
            const ruta = String(url).replace(/^https?:\/\/[^/]+/, '');
            let body;
            try { body = opts.body ? JSON.parse(opts.body) : undefined; } catch (e) { body = opts.body; }
            const req = { method, ruta, body, headers: opts.headers || {} };
            llamadas.push(req);
            const r = rutas.find(x => x.method === method && (x.ruta instanceof RegExp ? x.ruta.test(ruta) : x.ruta === ruta));
            let [status, datos] = r ? (typeof r.respuesta === 'function' ? r.respuesta(req) : r.respuesta) : [404, { error: 'no encontrado' }];
            if (status === 'network') throw new TypeError('Failed to fetch');
            const texto = datos === undefined ? '' : (typeof datos === 'string' ? datos : JSON.stringify(datos));
            return { ok: status >= 200 && status < 300, status, text: async () => texto };
        },
        de(method, ruta) { return llamadas.filter(c => c.method === method && (ruta instanceof RegExp ? ruta.test(c.ruta) : c.ruta === ruta)); }
    };
}

// ---------------------------------------------------------------- STOMP simulado
function crearStomp() {
    const clientes = [];
    const Stomp = {
        over(socket) {
            const c = {
                socket, connected: false, debug: () => {}, subs: [], enviados: [], desconectado: false,
                connect(headers, ok, error) { this.headers = headers; this._ok = ok; this._error = error; },
                subscribe(destino, cb, headers) { const s = { destino, cb, headers }; this.subs.push(s); return { unsubscribe() {} }; },
                send(destino, headers, body) {
                    if (this.fallarEnvio) throw new Error('socket cerrado');
                    this.enviados.push({ destino, body: JSON.parse(body) });
                },
                disconnect() { this.desconectado = true; this.connected = false; },
                // ---- control desde la prueba ----
                abrir() { this.connected = true; this._ok(); },
                fallar(mensaje) { this.connected = false; this._error(mensaje ? { headers: { message: mensaje } } : 'cerrado'); },
                emitir(destino, objeto) { this.subs.filter(s => s.destino === destino).forEach(s => s.cb({ body: JSON.stringify(objeto) })); }
            };
            clientes.push(c);
            return c;
        }
    };
    return { Stomp, clientes, get ultimo() { return clientes[clientes.length - 1]; } };
}

// ---------------------------------------------------------------- Leaflet simulado
function crearLeaflet(document) {
    const toLatLng = (p) => Array.isArray(p) ? { lat: p[0], lng: p[1] } : { lat: p.lat, lng: p.lng };
    function distancia(a, b) {
        a = toLatLng(a); b = toLatLng(b);
        const R = 6371e3, r = Math.PI / 180;
        const dLat = (b.lat - a.lat) * r, dLon = (b.lng - a.lng) * r;
        const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
        return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
    function capa(latlng, opciones) {
        return {
            latlng: latlng ? toLatLng(latlng) : null, opciones, popup: null, popupAbierto: false,
            addTo(m) { (m.capas || []).push(this); return this; },
            bindPopup(html) { this.popup = html; return this; },
            openPopup() { this.popupAbierto = true; return this; },
            setLatLng(p) { this.latlng = toLatLng(p); return this; },
            getLatLng() { return this.latlng; },
            setIcon(i) { this.opciones = Object.assign({}, this.opciones, { icon: i }); return this; },
            setContent(c) { this.popup = c; return this; },
            openOn() { this.popupAbierto = true; return this; }
        };
    }
    const mapas = [];
    const L = {
        map(id) {
            const handlers = {};
            const m = {
                id, capas: [], zoom: 16, vista: null, vuelos: [], encuadres: [],
                zoomControl: { setPosition() {} },
                setView(c, z) { this.vista = c; this.zoom = z; return this; },
                on(ev, fn) { (handlers[ev] = handlers[ev] || []).push(fn); return this; },
                disparar(ev, datos) { return Promise.all((handlers[ev] || []).map(fn => fn(datos))); },
                fitBounds(b, o) { this.encuadres.push(b); return this; },
                flyTo(c, z) { this.vuelos.push(c); return this; },
                getZoom() { return this.zoom; },
                distance: distancia
            };
            mapas.push(m);
            return m;
        },
        tileLayer: () => capa(),
        circle: (c, o) => capa(c, o),
        circleMarker: (c, o) => capa(c, o),
        marker: (c, o) => capa(c, o),
        popup: () => capa(),
        divIcon: (o) => o,
        latLng: (lat, lng) => ({ lat, lng }),
        latLngBounds: (puntos) => ({ puntos }),
        control(o) {
            return { o, addTo(m) { document.body.appendChild(this.onAdd(m)); return this; } };
        },
        DomUtil: { create(tag, clase) { const el = document.createElement(tag); el.className = clase; return el; } },
        DomEvent: {
            disableClickPropagation() {},
            on(el, ev, fn) { el.addEventListener(ev, fn); }
        }
    };
    return { L, mapas, get mapa() { return mapas[0]; } };
}

// ---------------------------------------------------------------- canvas y audio simulados
function contextoCanvas() {
    const base = {
        getImageData(x, y, w, h) {
            // Mezcla de píxeles: transparentes, grises (vidrios) y saturados (carrocería)
            const data = new Uint8ClampedArray(w * h * 4);
            const muestras = [[0, 0, 0, 0], [200, 30, 30, 255], [120, 120, 120, 255], [240, 230, 200, 255], [20, 200, 40, 255], [30, 40, 220, 255], [250, 250, 250, 255], [90, 60, 20, 255]];
            for (let i = 0; i < w * h; i++) data.set(muestras[i % muestras.length], i * 4);
            return { data, width: w, height: h };
        },
        measureText: () => ({ width: 10 }),
        createLinearGradient: () => ({ addColorStop() {} })
    };
    return new Proxy(base, { get: (t, p) => (p in t ? t[p] : () => {}), set: (t, p, v) => { t[p] = v; return true; } });
}

class AudioParam { setValueAtTime() {} linearRampToValueAtTime() {} }
class FakeAudioContext {
    constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; FakeAudioContext.instancias++; }
    resume() { this.state = 'running'; return Promise.resolve(); }
    createGain() { return { gain: Object.assign(new AudioParam(), { value: 1 }), connect(x) { return x; } }; }
    createOscillator() { return { frequency: new AudioParam(), connect(x) { return x; }, start() {}, stop() {} }; }
    createBuffer() { return {}; }
    createBufferSource() { return { connect() {}, start() {} }; }
}
FakeAudioContext.instancias = 0;

// ---------------------------------------------------------------- carga de páginas
const SCRIPTS_PAGINA = {
    'index.html': ['theme-boot.js', 'game-ui.js', 'session.js', 'index.js'],
    'lobby.html': ['theme-boot.js', 'game-ui.js', 'session.js', 'lobby.js'],
    'runner.html': ['theme-boot.js', 'game-ui.js', 'session.js', 'runner.js'],
    'dashboard.html': ['game-ui.js', 'session.js', 'dashboard.js']
};

export const PILOTO = { username: 'Piloto1', email: 'piloto1@mail.escuelaing.edu.co', racesCount: 3, winsCount: 1 };

/**
 * Abre una página del juego.
 * @param {string} pagina 'index.html', 'lobby.html', 'runner.html' o 'dashboard.html'
 * @param {object} o query, sesion (true por defecto), storage, ancho, alto, movil, gps, reducedMotion,
 *                   sinGameUI, sinGeolocalizacion, sinAudio, preparar(api) para registrar rutas antes de cargar
 */
export function abrir(pagina, o = {}) {
    const html = fs.readFileSync(path.join(STATIC, pagina), 'utf8')
        .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
        .replace(/<link\b[^>]*>/gi, '');
    const errores = [];
    const consola = new VirtualConsole();
    consola.on('jsdomError', (e) => { if (!/navigation|Not implemented/i.test(String(e && e.message))) errores.push(e); });
    const dom = new JSDOM(html, {
        url: `https://ecirace.test/${pagina}${o.query || ''}`,
        runScripts: 'outside-only',
        pretendToBeVisual: true,
        virtualConsole: consola
    });
    const w = dom.window;
    const reloj = crearReloj();
    const api = crearApi();
    const stomp = crearStomp();
    const leaflet = crearLeaflet(w.document);
    const gps = { watchers: new Map(), siguiente: 1, actual: o.gps || null };

    w.__coverage__ = globalThis.__coverage__;
    w.setTimeout = reloj.setTimeout; w.setInterval = reloj.setInterval;
    w.clearTimeout = reloj.clear; w.clearInterval = reloj.clear;
    w.requestAnimationFrame = reloj.raf; w.cancelAnimationFrame = reloj.cancelRaf;
    w.fetch = (url, opts) => api.fetch(url, opts);
    // El reloj de la página avanza con el reloj simulado (para los timestamps de la telemetría)
    const inicio = Date.parse('2026-11-21T15:00:00Z');
    w.Date.now = () => inicio + reloj.now;
    // jsdom no implementa innerText: se comporta como textContent
    Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
        configurable: true,
        get() { return this.textContent; },
        set(v) { this.textContent = v; }
    });
    w.Stomp = stomp.Stomp;
    w.WebSocket = class { constructor(url) { this.url = url; } };
    w.L = leaflet.L;
    w.alert = () => {};
    w.confirm = () => true;
    w.scrollTo = () => {};
    w.HTMLElement.prototype.scrollIntoView = function () {};
    w.matchMedia = (q) => ({ matches: (!!o.reducedMotion && /reduce/.test(q)) || (!!o.estrecho && /max-width/.test(q)) });
    w.HTMLCanvasElement.prototype.getContext = function () { return contextoCanvas(); };
    if (!o.sinAudio) w.AudioContext = FakeAudioContext;
    Object.defineProperty(w, 'innerWidth', { value: o.ancho || 1280, configurable: true });
    Object.defineProperty(w, 'innerHeight', { value: o.alto || 800, configurable: true });
    Object.defineProperty(w, 'isSecureContext', { value: o.inseguro ? false : true, configurable: true });
    Object.defineProperty(w.navigator, 'userAgent', { value: o.movil ? 'Mozilla/5.0 (iPhone)' : 'Mozilla/5.0 (Windows NT 10.0)', configurable: true });
    if (!o.sinGeolocalizacion) {
        Object.defineProperty(w.navigator, 'geolocation', {
            configurable: true,
            value: {
                watchPosition(ok, err) { const id = gps.siguiente++; gps.watchers.set(id, { ok, err }); return id; },
                clearWatch(id) { gps.watchers.delete(id); },
                getCurrentPosition(ok, err) { if (gps.actual) ok({ coords: gps.actual }); else err(new Error('sin GPS')); }
            }
        });
    }
    Object.defineProperty(w.navigator, 'wakeLock', {
        configurable: true,
        value: { request: async () => ({ addEventListener() {}, release() {} }) }
    });
    Object.defineProperty(w.navigator, 'clipboard', {
        configurable: true,
        value: { writeText: o.clipboardFalla ? () => Promise.reject(new Error('denegado')) : () => Promise.resolve() }
    });

    // Las imágenes no se descargan en jsdom: se marcan como cargadas y se avisa con el evento "load"
    const descSrc = Object.getOwnPropertyDescriptor(w.HTMLImageElement.prototype, 'src');
    Object.defineProperty(w.HTMLImageElement.prototype, 'src', {
        configurable: true,
        get() { return descSrc.get.call(this); },
        set(v) { descSrc.set.call(this, v); const img = this; reloj.setTimeout(() => img.dispatchEvent(new w.Event('load')), 1); }
    });
    Object.defineProperty(w.HTMLImageElement.prototype, 'naturalWidth', { configurable: true, get() { return 4; } });
    Object.defineProperty(w.HTMLImageElement.prototype, 'naturalHeight', { configurable: true, get() { return 4; } });
    Object.defineProperty(w.HTMLImageElement.prototype, 'complete', { configurable: true, get() { return true; } });

    // Sesión iniciada por defecto (las páginas internas la exigen)
    if (o.sesion !== false) {
        w.localStorage.setItem('ecirace_token', 'token-de-prueba');
        w.localStorage.setItem('ecirace_profile', JSON.stringify(o.perfil || PILOTO));
    }
    Object.entries(o.storage || {}).forEach(([k, v]) => w.localStorage.setItem(k, v));
    if (o.preparar) o.preparar(api);

    const errorScripts = [];
    const scripts = SCRIPTS_PAGINA[pagina].filter(s => !(o.sinGameUI && s === 'game-ui.js'));
    for (const s of scripts) {
        try {
            w.eval(instrumentar(`src/main/resources/static/js/${s}`));
        } catch (e) {
            errorScripts.push(e);
            break;
        }
    }
    w.document.dispatchEvent(new w.Event('DOMContentLoaded', { bubbles: true }));

    const $ = (sel) => w.document.querySelector(sel);
    return {
        w, dom, reloj, api, stomp, leaflet, gps, errores, errorScripts, $,
        click(sel) { const el = typeof sel === 'string' ? $(sel) : sel; el.dispatchEvent(new w.MouseEvent('click', { bubbles: true })); },
        teclear(sel, key) { const el = typeof sel === 'string' ? $(sel) : sel; el.dispatchEvent(new w.KeyboardEvent('keydown', { key, bubbles: true })); },
        escribir(sel, valor) { $(sel).value = valor; },
        /** Deja correr las promesas pendientes (respuestas de la API simulada). */
        async esperar(vueltas = 6) { for (let i = 0; i < vueltas; i++) await new Promise(r => setImmediate(r)); },
        /** Avanza el reloj y luego deja correr las promesas que eso dispare. */
        async avanzar(ms) { reloj.advance(ms); await this.esperar(); },
        async cerrar() { await this.esperar(10); w.close(); }
    };
}

export { FakeAudioContext };
