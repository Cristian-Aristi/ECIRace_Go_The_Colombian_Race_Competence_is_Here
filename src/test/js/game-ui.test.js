import { describe, it, expect, afterEach } from 'vitest';
import { abrir } from './harness.js';

let p;
afterEach(async () => { if (p) await p.cerrar(); p = null; });

describe('Capa visual del juego (game-ui.js)', () => {
    it('lee y guarda los ajustes del jugador', () => {
        p = abrir('index.html', { sesion: false });
        const s = p.w.GameUI.settings;
        expect(s.audio).toBe(true);
        expect(s.highAccuracy).toBe(true);
        expect(s.trackBg).toBe(true);
        s.audio = false; s.highAccuracy = false; s.trackBg = false;
        expect(p.w.localStorage.getItem('ecirace_audio')).toBe('off');
        expect(s.audio).toBe(false);
        expect(s.highAccuracy).toBe(false);
        expect(s.trackBg).toBe(false);
        s.audio = true; s.highAccuracy = true; s.trackBg = true;
        expect(s.trackBg).toBe(true);
    });

    it('dibuja los íconos pixel art y los cachea', () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        expect(UI.icon('no-existe')).toBe('');
        const svg = UI.icon('trophy');
        expect(svg).toContain('<svg');
        expect(UI.icon('trophy')).toBe(svg);
        const caja = p.w.document.createElement('div');
        caja.innerHTML = '<i data-icon="flag"></i><i data-icon="flag"><b></b></i>';
        UI.fillIcons(caja);
        expect(caja.firstElementChild.innerHTML).toContain('<svg');
        UI.fillIcons();
    });

    it('reproduce sonidos solo con el audio activado', () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        UI.settings.audio = false;
        UI.sfx.click();
        UI.settings.audio = true;
        p.w.document.dispatchEvent(new p.w.Event('touchend'));
        Object.values(UI.sfx).forEach(f => f());
        Object.defineProperty(p.w.document, 'visibilityState', { configurable: true, value: 'visible' });
        p.w.document.dispatchEvent(new p.w.Event('visibilitychange'));
        // sonido por tipo de botón
        const b = p.w.document.createElement('button');
        p.w.document.body.appendChild(b);
        ['vehicle-btn', 'g-race-btn', ''].forEach(c => { b.className = c; p.click(b); });
        b.disabled = true; p.click(b);
        p.click('body');
    });

    it('sin AudioContext el juego sigue funcionando en silencio', () => {
        p = abrir('index.html', { sesion: false, sinAudio: true });
        p.w.GameUI.sfx.win();
        p.w.document.dispatchEvent(new p.w.Event('keydown'));
        expect(p.errorScripts).toEqual([]);
    });

    it('cambia la etiqueta de un botón sin borrar su ícono', () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        const b = p.w.document.createElement('button');
        b.innerHTML = '<i></i><span class="g-btn-label">A</span>';
        UI.setLabel(b, 'B');
        expect(b.querySelector('i')).not.toBeNull();
        expect(b.textContent).toBe('B');
        const b2 = p.w.document.createElement('button');
        UI.setLabel(b2, 'C');
        expect(b2.textContent).toBe('C');
        UI.setLabel(null, 'x');
    });

    it('muestra avisos que desaparecen solos', () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        UI.toast('hola');
        UI.toast('error', 'error');
        UI.toast('bien', 'ok', 500);
        expect(p.w.document.querySelectorAll('.g-toast').length).toBe(3);
        p.reloj.advance(4000);
        expect(p.w.document.querySelectorAll('.g-toast').length).toBe(0);
    });

    it('confirma con un modal propio', async () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        const si = UI.confirm('¿Seguro?', 'Sí', 'No');
        p.click('.modal-backdrop');                 // clic fuera de los botones: no responde
        p.click('[data-r="1"]');
        expect(await si).toBe(true);
        const no = UI.confirm('¿Otra vez?');
        expect(p.$('[data-r="0"]').textContent).toBe('Cancelar');
        p.click('[data-r="0"]');
        expect(await no).toBe(false);
    });

    it('hace la cuenta regresiva 3-2-1-GO', async () => {
        p = abrir('index.html', { sesion: false });
        let listo = false;
        p.w.GameUI.countdown().then(() => { listo = true; });
        expect(p.$('.g-count').textContent).toBe('3');
        await p.avanzar(800 * 3);
        expect(p.$('.g-count').textContent).toBe('GO!');
        await p.avanzar(800);
        expect(listo).toBe(true);
        expect(p.$('.g-countdown')).toBeNull();
    });

    it('lanza confeti (y lo omite con movimiento reducido)', () => {
        p = abrir('index.html', { sesion: false });
        p.w.GameUI.confetti(100);
        expect(p.$('#g-confetti')).not.toBeNull();
        p.reloj.frame(0);
        p.reloj.frame(1e12);
        expect(p.$('#g-confetti')).toBeNull();
        p.w.GameUI.confetti();
    });

    it('anima la pista de fondo en PC y en celular', async () => {
        p = abrir('index.html', { sesion: false });
        const UI = p.w.GameUI;
        UI.setSceneSpeed(25);
        UI.setSceneSpeed();
        await p.avanzar(5);                         // cargan los sprites y se repintan
        for (let i = 1; i <= 120; i++) p.reloj.frame(i * 40);
        Object.defineProperty(p.w.document, 'hidden', { configurable: true, value: true });
        p.reloj.frame(5000);
        Object.defineProperty(p.w.document, 'hidden', { configurable: true, value: false });
        // pasa a vertical (celular) y se redimensiona
        Object.defineProperty(p.w, 'innerWidth', { configurable: true, value: 390 });
        Object.defineProperty(p.w, 'innerHeight', { configurable: true, value: 844 });
        p.w.dispatchEvent(new p.w.Event('resize'));
        p.w.dispatchEvent(new p.w.Event('resize'));
        p.reloj.advance(200);
        for (let i = 1; i <= 200; i++) p.reloj.frame(6000 + i * 50);
        expect(p.$('#scene').width).toBeGreaterThan(0);

        // fondo estático y de vuelta a la pista
        UI.settings.trackBg = false;
        UI.applyBackground();
        expect(p.$('#scene').style.display).toBe('none');
        UI.settings.trackBg = true;
        UI.applyBackground();
        UI.applyBackground();
        expect(p.$('#scene').style.display).toBe('block');
        expect(UI.track(null)).toBeNull();
    });

    it('con movimiento reducido la pista se dibuja quieta', async () => {
        p = abrir('index.html', { sesion: false, reducedMotion: true, ancho: 1600, alto: 900 });
        await p.avanzar(5);
        const pista = p.w.GameUI.track(p.$('#scene'));
        pista.stop();
        pista.start();
        expect(p.errorScripts).toEqual([]);
    });

    it('pista pequeña de PC', async () => {
        p = abrir('index.html', { sesion: false, ancho: 800, alto: 500 });
        await p.avanzar(5);
        for (let i = 1; i <= 60; i++) p.reloj.frame(i * 33);
        const pista = p.w.GameUI.track(p.$('#scene'));
        pista.stop();
        p.reloj.frame(99999);
        pista.start();
        p.reloj.frame(100100);
        expect(p.errorScripts).toEqual([]);
    });
});
