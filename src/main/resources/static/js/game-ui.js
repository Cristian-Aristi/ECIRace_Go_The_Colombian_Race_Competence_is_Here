// src/main/resources/static/js/game-ui.js
// Capa visual de "juego" compartida por todas las páginas:
//   · fondo pixel art animado (pista en perspectiva tipo arcade)
//   · íconos pixel art (<span class="g-ico" data-icon="trophy">)
//   · efectos de sonido sintetizados (sin archivos de audio)
//   · avisos (toast), confirmación, cuenta regresiva y confeti
//   · ajustes del jugador guardados en localStorage
// No contiene lógica de carrera: los .js de cada página la siguen manejando.

(function () {
    'use strict';

    const GameUI = {};

    // ------------------------------------------------------------------
    // Ajustes del jugador
    // ------------------------------------------------------------------
    function leer(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }
    function guardar(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }

    GameUI.settings = {
        get audio() { return leer('ecirace_audio', 'on') !== 'off'; },
        set audio(v) { guardar('ecirace_audio', v ? 'on' : 'off'); },
        get highAccuracy() { return leer('ecirace_high_accuracy', 'on') !== 'off'; },
        set highAccuracy(v) { guardar('ecirace_high_accuracy', v ? 'on' : 'off'); },
        // Fondo: pista animada (por defecto) o la ilustración estática
        get trackBg() { return leer('ecirace_bg', 'pista') !== 'imagen'; },
        set trackBg(v) { guardar('ecirace_bg', v ? 'pista' : 'imagen'); }
    };

    // ------------------------------------------------------------------
    // Íconos pixel art (cada letra es un color; '.' es transparente)
    // ------------------------------------------------------------------
    const PAL = {
        k: '#0b1636', Y: '#ffe45c', y: '#ffad00', o: '#ff7a1a', w: '#f2f6ff', g: '#9fb0d6',
        b: '#4c9bff', B: '#1b2c5c', r: '#ff4b55', R: '#b51e2a', G: '#3fcf6a', D: '#1f7a3a',
        n: '#8a5a2b', p: '#8a5cff', P: '#4c2fc4', t: '#2de3c6', s: '#ffd2a1'
    };

    const SPRITES = {
        helmet: [
            '.....kkkkkk.....',
            '...kkYYwYYYkk...',
            '..kYYYwYYYYYyk..',
            '.kYYYYwYYYYYYyk.',
            '.kYYYYwYYYYYYyk.',
            'kYYYkkkkkkkkkYyk',
            'kYYkBBBBBBBBBkyk',
            'kYYkBbBBBBBBBkyk',
            'kYYkBBbBBBBBBkyk',
            'kYYYkkkkkkkkkYyk',
            'kYYYYYYYYYYYYyyk',
            '.kYYkkkkkkkkYyk.',
            '.kyykwwwwwwkyyk.',
            '..kkk......kkk..'
        ],
        crown: [
            '.k...kk...k.',
            'kYk.kYYk.kYk',
            'kYYkYYYYkYYk',
            'kYYYYYYYYYYk',
            'kYYrYYbYYrYk',
            'kYYYYYYYYYYk',
            'kkkkkkkkkkkk'
        ],
        trophy: [
            '..kkkkkkkk..',
            'kkkYYYYYykkk',
            'kYkYYYYYykYk',
            'kYkYYYYYykYk',
            '.kkYYYYYykk.',
            '...kYYYyk...',
            '....kYyk....',
            '.....kk.....',
            '....kyyk....',
            '...kyyyyk...',
            '..kkkkkkkk..',
            '..knnnnnnk..'
        ],
        star: [
            '.....kk.....',
            '....kYYk....',
            '....kYYk....',
            'kkkkkYYkkkkk',
            'kYYYYYYYYyyk',
            '.kYYYYYYyyk.',
            '..kYYYYYyk..',
            '..kYYYYYyk..',
            '.kYYYkkYyyk.',
            '.kYyk..kyyk.',
            'kyk......kyk',
            'kk........kk'
        ],
        flag: [
            'kkkkkkkkkkkk',
            'kwwkkwwkkwwk',
            'kwwkkwwkkwwk',
            'kkkwwkkwwkkk',
            'kkkwwkkwwkkk',
            'kwwkkwwkkwwk',
            'kkkkkkkkkkkk',
            'kn..........',
            'kn..........',
            'kn..........',
            'kn..........',
            'kk..........'
        ],
        pennant: [
            'kk..........',
            'knkkkkkk....',
            'knrrrrrrkk..',
            'knrrwwrrrrk.',
            'knppppppk...',
            'knpppkk.....',
            'knkkk.......',
            'kn..........',
            'kn..........',
            'kn..........',
            'kn..........',
            'kk..........'
        ],
        pencil: [
            '........kkk.',
            '.......krrrk',
            '......kYkrrk',
            '.....kYYYkk.',
            '....kYYYk...',
            '...kYYYk....',
            '..kYYYk.....',
            '.kwYYk......',
            '.kwwk.......',
            'kkkk........'
        ],
        users: [
            '....kkk.....',
            '...kwwwk.kk.',
            '...kwwwkkggk',
            '...kwwwkkggk',
            '....kkk..kk.',
            '..kkkkkk.kk.',
            '.kwwwwwwkggk',
            '.kwwwwwwkggk',
            '.kwwwwwwkggk',
            '.kkkkkkkkkk.'
        ],
        wifi: [
            '...kkkkkk...',
            '.kkYYYYYYkk.',
            'kYYkkkkkkYYk',
            'kkk.kkkk.kkk',
            '...kYYYYk...',
            '..kYkkkkYk..',
            '...k.kk.k...',
            '....kYYk....',
            '....kYYk....',
            '.....kk.....'
        ],
        map: [
            'kkkkkkkkkkkk',
            'kGGGGbbbGGGk',
            'kGGGbbbGrrGk',
            'kGGbbbGrrrrk',
            'kGbbbGGrrrrk',
            'kbbbGGGGrrGk',
            'kbbGGGGGkrGk',
            'kbGGGwwGGkGk',
            'kGGGwwGGGGGk',
            'kGGwwGGGGGGk',
            'kGwwGGGGGGGk',
            'kkkkkkkkkkkk'
        ],
        gear: [
            '....kkkk....',
            '.kk.kwwk.kk.',
            'kwwkkwwkkwwk',
            'kwwwwwwwwwwk',
            '.kwwwkkwwwk.',
            'kkwwk..kwwkk',
            'kwwwk..kwwwk',
            '.kwwwkkwwwk.',
            'kwwwwwwwwwwk',
            'kwwkkwwkkwwk',
            '.kk.kwwk.kk.',
            '....kkkk....'
        ],
        speaker: [
            '.....kk..k..',
            '....kwk...k.',
            'kkkkwwk.k..k',
            'kwwwwwk..k.k',
            'kwwwwwk..k.k',
            'kwwwwwk..k.k',
            'kkkkwwk.k..k',
            '....kwk...k.',
            '.....kk..k..'
        ],
        tag: [
            '....kkkkkkk.',
            '...kYYYYYYYk',
            '..kYYYYYYYYk',
            '.kYYkkYYYYYk',
            'kYYYkkYYYYYk',
            '.kYYYYYYYYYk',
            '..kYYYYYYYYk',
            '...kYYYYYYYk',
            '....kkkkkkk.'
        ],
        target: [
            '.....kk.....',
            '...kkwwkk...',
            '..kw.kk.wk..',
            '.kw..kk..wk.',
            '.k...kk...k.',
            'kkkkkrrkkkkk',
            'kkkkkrrkkkkk',
            '.k...kk...k.',
            '.kw..kk..wk.',
            '..kw.kk.wk..',
            '...kkwwkk...',
            '.....kk.....'
        ],
        joystick: [
            '....kkk.....',
            '...krrrk....',
            '...krrrk....',
            '....kkk.....',
            '.....k......',
            '.....k......',
            '.kkkkkkkkk..',
            'kbbbbbbbbbk.',
            'kbbYYbbrrbk.',
            'kBBBBBBBBBk.',
            '.kkkkkkkkk..'
        ],
        stop: [
            '..kkkkkkkk..',
            '.krrrrrrrrk.',
            'krrrrrrrrrrk',
            'krrwwwwwwrrk',
            'krrwwwwwwrrk',
            'krrwwwwwwrrk',
            'krrwwwwwwrrk',
            'krrrrrrrrrrk',
            '.krrrrrrrrk.',
            '..kkkkkkkk..'
        ],
        monitor: [
            'kkkkkkkkkkkk',
            'kbbbbbbbbbbk',
            'kbwbbbbbbbbk',
            'kbbbbbbbbbbk',
            'kbbbbbbbbbbk',
            'kbbbbbbbbbbk',
            'kkkkkkkkkkkk',
            '.....kk.....',
            '....kggk....',
            '..kkkkkkkk..'
        ],
        phone: [
            '..kkkkkkk...',
            '..kgggggk...',
            '..kbbbbbk...',
            '..kbwbbbk...',
            '..kbbbbbk...',
            '..kbbbbbk...',
            '..kbbbbbk...',
            '..kgggggk...',
            '..kggkggk...',
            '..kkkkkkk...'
        ],
        home: [
            '.....kk.....',
            '....krrk....',
            '...krrrrk...',
            '..krrrrrrk..',
            '.krrrrrrrrk.',
            'kkkkkkkkkkkk',
            '.kwwwwwwwwk.',
            '.kwwkkkwwwk.',
            '.kwwknkwwwk.',
            '.kwwknkwwwk.',
            '.kkkkkkkkkk.'
        ],
        door: [
            '.kkkkkkk....',
            '.knnnnnk....',
            '.knnnnnk..k.',
            '.knnnnnk.kk.',
            '.knnnYnkkwwk',
            '.knnnnnk.kk.',
            '.knnnnnk..k.',
            '.knnnnnk....',
            '.kkkkkkk....'
        ]
    };

    function pixelSvg(rows) {
        const h = rows.length;
        const w = Math.max.apply(null, rows.map(r => r.length));
        let rects = '';
        rows.forEach((row, y) => {
            let x = 0;
            while (x < row.length) {
                const c = row[x];
                if (!PAL[c]) { x++; continue; }
                let x2 = x;
                while (x2 < row.length && row[x2] === c) x2++;
                rects += `<rect x="${x}" y="${y}" width="${x2 - x}" height="1" fill="${PAL[c]}"/>`;
                x = x2;
            }
        });
        return `<svg viewBox="0 0 ${w} ${h}" shape-rendering="crispEdges" aria-hidden="true" focusable="false">${rects}</svg>`;
    }

    const iconCache = {};
    GameUI.icon = function (name) {
        if (!SPRITES[name]) return '';
        if (!iconCache[name]) iconCache[name] = pixelSvg(SPRITES[name]);
        return iconCache[name];
    };
    GameUI.fillIcons = function (root) {
        (root || document).querySelectorAll('[data-icon]').forEach(el => {
            if (!el.firstElementChild) el.innerHTML = GameUI.icon(el.getAttribute('data-icon'));
        });
    };

    // ------------------------------------------------------------------
    // Sonido (WebAudio, ondas cuadradas tipo consola de 8 bits)
    // ------------------------------------------------------------------
    let audioCtx = null;
    let master = null;
    let desbloqueado = false;
    const VOLUMEN_MAESTRO = 1.0;

    // iPhone (Safari 17+): reproducir aunque el interruptor de silencio esté activado
    try {
        if (navigator.audioSession) navigator.audioSession.type = 'playback';
    } catch (e) { /* no soportado */ }

    function ctx() {
        if (!GameUI.settings.audio) return null;
        try {
            if (!audioCtx) {
                const C = window.AudioContext || window.webkitAudioContext;
                if (!C) return null;
                audioCtx = new C();
                master = audioCtx.createGain();
                master.gain.value = VOLUMEN_MAESTRO;
                master.connect(audioCtx.destination);
            }
            if (audioCtx.state !== 'running') audioCtx.resume();
        } catch (e) { return null; }
        return audioCtx;
    }

    // Safari en iPhone solo deja arrancar el audio dentro de un toque completo
    // (touchend / click), no al apoyar el dedo. En el primero se "desbloquea"
    // reproduciendo un instante de silencio.
    function desbloquearAudio() {
        if (desbloqueado) return;
        const ac = ctx();
        if (!ac) return;
        try {
            const buf = ac.createBuffer(1, 1, 22050);
            const src = ac.createBufferSource();
            src.buffer = buf;
            src.connect(ac.destination);
            src.start(0);
            if (ac.state !== 'running') ac.resume();
            desbloqueado = ac.state === 'running';
        } catch (e) {}
    }
    ['touchend', 'click', 'keydown'].forEach(ev =>
        document.addEventListener(ev, desbloquearAudio, { capture: true, passive: true }));
    // Si el navegador suspende el audio al cambiar de pestaña, se reanuda al volver
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && audioCtx && audioCtx.state !== 'running') {
            try { audioCtx.resume(); } catch (e) {}
        }
    });

    function tone(freq, dur, opts) {
        const o = opts || {};
        const ac = ctx();
        if (!ac) return;
        const t0 = ac.currentTime + 0.005 + (o.delay || 0);
        const osc = ac.createOscillator();
        const gain = ac.createGain();
        osc.type = o.type || 'square';
        osc.frequency.setValueAtTime(freq, t0);
        if (o.slide) osc.frequency.linearRampToValueAtTime(o.slide, t0 + dur);
        const vol = o.vol || 0.12;
        gain.gain.setValueAtTime(0.0001, t0);
        gain.gain.linearRampToValueAtTime(vol, t0 + 0.008);
        gain.gain.setValueAtTime(vol, t0 + Math.max(0.01, dur - 0.04));
        gain.gain.linearRampToValueAtTime(0.0001, t0 + dur);
        osc.connect(gain).connect(master || ac.destination);
        osc.start(t0);
        osc.stop(t0 + dur + 0.02);
    }
    function melody(notes, step, opts) {
        notes.forEach((f, i) => { if (f) tone(f, step * 0.9, Object.assign({}, opts, { delay: i * step })); });
    }
    GameUI.sfx = {
        click() { tone(740, 0.07, { vol: 0.12 }); tone(1110, 0.05, { vol: 0.08, delay: 0.05 }); },
        select() { tone(523, 0.08, { vol: 0.13 }); tone(784, 0.12, { vol: 0.13, delay: 0.07 }); },
        join() { melody([523, 659, 784, 1047], 0.09, { vol: 0.14 }); },
        coin() { tone(988, 0.08, { vol: 0.14 }); tone(1319, 0.22, { vol: 0.14, delay: 0.08 }); },
        error() { tone(220, 0.28, { type: 'sawtooth', slide: 110, vol: 0.14 }); },
        beep() { tone(440, 0.25, { vol: 0.18 }); },
        go() { tone(880, 0.6, { vol: 0.2 }); },
        win() { melody([523, 659, 784, 1047, 0, 784, 1047, 1047], 0.12, { vol: 0.16 }); },
        lose() { melody([392, 330, 262, 196], 0.18, { vol: 0.15, type: 'triangle' }); },
        start() { melody([262, 330, 392, 523, 659], 0.07, { vol: 0.14 }); },
        // Prueba audible desde Ajustes
        test() { melody([523, 659, 784], 0.1, { vol: 0.16 }); }
    };

    // Sonido en botones. Se usa "click" (no pointerdown) porque es el único
    // evento con el que Safari de iPhone permite reproducir audio.
    document.addEventListener('click', (e) => {
        const el = e.target.closest('button, .room-pill, .role-tab, a.g-btn');
        if (!el || el.disabled) return;
        if (el.classList.contains('vehicle-btn')) GameUI.sfx.select();
        else if (el.classList.contains('g-race-btn')) GameUI.sfx.start();
        else GameUI.sfx.click();
    }, { passive: true });

    // ------------------------------------------------------------------
    // Etiquetas de botones (los .js cambian el texto sin borrar el ícono)
    // ------------------------------------------------------------------
    GameUI.setLabel = function (btn, text) {
        if (!btn) return;
        const label = btn.querySelector('.g-btn-label');
        if (label) label.textContent = text;
        else btn.textContent = text;
    };

    // ------------------------------------------------------------------
    // Avisos y confirmación
    // ------------------------------------------------------------------
    let toastBox = null;
    GameUI.toast = function (msg, type, ms) {
        if (!toastBox) {
            toastBox = document.createElement('div');
            toastBox.className = 'g-toasts';
            toastBox.setAttribute('role', 'status');
            toastBox.setAttribute('aria-live', 'polite');
            document.body.appendChild(toastBox);
        }
        const t = document.createElement('div');
        t.className = 'g-toast' + (type ? ' g-toast--' + type : '');
        t.textContent = msg;
        toastBox.appendChild(t);
        if (type === 'error') GameUI.sfx.error();
        else if (type === 'ok') GameUI.sfx.coin();
        setTimeout(() => {
            t.classList.add('is-leaving');
            setTimeout(() => t.remove(), 200);
        }, ms || 3200);
    };

    GameUI.confirm = function (msg, okText, cancelText) {
        return new Promise(resolve => {
            const back = document.createElement('div');
            back.className = 'modal-backdrop';
            back.style.display = 'flex';
            back.innerHTML = `
                <div class="modal-content g-panel" role="dialog" aria-modal="true">
                    <p class="g-copy" style="margin:0; font-size:1.45rem;"></p>
                    <div class="g-two">
                        <button type="button" class="g-btn g-btn--gold" data-r="1"></button>
                        <button type="button" class="g-btn" data-r="0"></button>
                    </div>
                </div>`;
            back.querySelector('p').textContent = msg;
            back.querySelector('[data-r="1"]').textContent = okText || 'Sí';
            back.querySelector('[data-r="0"]').textContent = cancelText || 'Cancelar';
            back.addEventListener('click', (e) => {
                const b = e.target.closest('[data-r]');
                if (!b) return;
                back.remove();
                resolve(b.getAttribute('data-r') === '1');
            });
            document.body.appendChild(back);
            back.querySelector('[data-r="1"]').focus();
        });
    };

    // ------------------------------------------------------------------
    // Cuenta regresiva de largada (semáforo 3-2-1-GO)
    // ------------------------------------------------------------------
    GameUI.countdown = function () {
        return new Promise(resolve => {
            const ov = document.createElement('div');
            ov.className = 'g-countdown';
            ov.setAttribute('aria-live', 'assertive');
            ov.innerHTML = '<div class="g-lights"><i></i><i></i><i></i></div><div class="g-count"></div>';
            document.body.appendChild(ov);
            const lights = ov.querySelectorAll('.g-lights i');
            const num = ov.querySelector('.g-count');
            const pasos = ['3', '2', '1', 'GO!'];
            let i = 0;
            function paso() {
                if (i >= pasos.length) {
                    ov.remove();
                    resolve();
                    return;
                }
                const v = pasos[i];
                num.textContent = v;
                num.classList.toggle('is-go', v === 'GO!');
                num.style.animation = 'none';
                void num.offsetWidth;
                num.style.animation = '';
                lights.forEach((l, idx) => {
                    l.classList.toggle('is-red', v !== 'GO!' && idx <= i);
                    l.classList.toggle('is-green', v === 'GO!');
                });
                if (v === 'GO!') GameUI.sfx.go(); else GameUI.sfx.beep();
                i++;
                setTimeout(paso, v === 'GO!' ? 750 : 800);
            }
            paso();
        });
    };

    // ------------------------------------------------------------------
    // Confeti pixelado
    // ------------------------------------------------------------------
    GameUI.confetti = function (ms) {
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        const c = document.createElement('canvas');
        c.id = 'g-confetti';
        const scale = 3;
        c.width = Math.ceil(window.innerWidth / scale);
        c.height = Math.ceil(window.innerHeight / scale);
        c.style.width = '100vw';
        c.style.height = '100vh';
        document.body.appendChild(c);
        const g = c.getContext('2d');
        const colores = ['#ffc928', '#ffe45c', '#2de3c6', '#ff4b55', '#ffffff', '#4c9bff', '#8a5cff'];
        const piezas = Array.from({ length: 140 }, () => ({
            x: Math.random() * c.width,
            y: -Math.random() * c.height * 0.6,
            vx: (Math.random() - 0.5) * 0.8,
            vy: 0.6 + Math.random() * 1.2,
            s: Math.random() < 0.3 ? 2 : 1,
            col: colores[Math.floor(Math.random() * colores.length)],
            fase: Math.random() * 6
        }));
        const fin = performance.now() + (ms || 3500);
        function frame(now) {
            g.clearRect(0, 0, c.width, c.height);
            piezas.forEach(p => {
                p.fase += 0.15;
                p.x += p.vx + Math.sin(p.fase) * 0.3;
                p.y += p.vy;
                g.fillStyle = p.col;
                g.fillRect(Math.round(p.x), Math.round(p.y), p.s + (Math.sin(p.fase) > 0 ? 1 : 0), p.s);
            });
            if (now < fin) requestAnimationFrame(frame); else c.remove();
        }
        requestAnimationFrame(frame);
    };

    // ------------------------------------------------------------------
    // Fondo: pista arcade en pixel art
    // ------------------------------------------------------------------
    function rng(seed) {
        return function () {
            seed |= 0; seed = seed + 0x6D2B79F5 | 0;
            let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
            t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
            return ((t ^ t >>> 14) >>> 0) / 4294967296;
        };
    }

    const LETRAS = {
        E: ['111', '100', '110', '100', '111'],
        C: ['111', '100', '100', '100', '111'],
        I: ['111', '010', '010', '010', '111']
    };
    function textoPixel(g, txt, x, y, color) {
        g.fillStyle = color;
        for (let i = 0; i < txt.length; i++) {
            const L = LETRAS[txt[i]];
            if (!L) continue;
            L.forEach((fila, fy) => {
                for (let fx = 0; fx < 3; fx++) if (fila[fx] === '1') g.fillRect(x + i * 4 + fx, y + fy, 1, 1);
            });
        }
    }

    let sceneSpeed = 1;
    GameUI.setSceneSpeed = function (kmh) {
        sceneSpeed = 0.8 + Math.min(40, Math.max(0, kmh || 0)) / 8;
    };

    // ------------------------------------------------------------------
    // Fondo: PISTA DE CARRERAS animada vista desde arriba.
    // Los autos (los mismos sprites del juego) corren y se adelantan.
    // Celular (vertical): los autos suben. PC (horizontal): van a la derecha.
    // Todo se dibuja en "coordenadas de pista": x a lo largo, y a lo ancho.
    // ------------------------------------------------------------------
    GameUI.track = function (canvas) {
        if (!canvas || !canvas.getContext) return null;
        const g = canvas.getContext('2d');
        const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        const sprites = ['renault', 'spark', 'mazda'].map(n => {
            const im = new Image();
            im.src = 'img/' + n + '.png?v=2';
            return im;
        });
        const COLORES_RESPALDO = ['#f1e3c0', '#e8e020', '#e9edf2', '#e23a2f', '#2f6fe2', '#5fd23a', '#e23a2f'];

        // Repinta un sprite girando el tono de los píxeles saturados (la carrocería)
        function repintar(im, grados, saturar) {
            try {
                const c = document.createElement('canvas');
                c.width = im.naturalWidth; c.height = im.naturalHeight;
                const cx = c.getContext('2d');
                cx.drawImage(im, 0, 0);
                const d = cx.getImageData(0, 0, c.width, c.height), px = d.data;
                for (let i = 0; i < px.length; i += 4) {
                    if (px[i + 3] < 10) continue;
                    let r = px[i] / 255, gg = px[i + 1] / 255, b = px[i + 2] / 255;
                    const max = Math.max(r, gg, b), min = Math.min(r, gg, b), l = (max + min) / 2;
                    let h = 0, sat = 0;
                    if (max !== min) {
                        const dd = max - min;
                        sat = l > 0.5 ? dd / (2 - max - min) : dd / (max + min);
                        h = max === r ? (gg - b) / dd + (gg < b ? 6 : 0) : max === gg ? (b - r) / dd + 2 : (r - gg) / dd + 4;
                        h /= 6;
                    }
                    if (sat < 0.25 && !saturar) continue;          // vidrios, llantas y cromados quedan igual
                    if (saturar && (l > 0.93 || l < 0.42 || sat > 0.6)) continue;   // en el Renault solo la carrocería clara
                    h = saturar ? (grados / 360 + 1) % 1 : (h + grados / 360 + 1) % 1;
                    const s2 = saturar ? Math.max(sat, saturar) : sat;
                    let l2 = saturar ? l * 0.62 : l;
                    const q = l2 < 0.5 ? l2 * (1 + s2) : l2 + s2 - l2 * s2, p2 = 2 * l2 - q;
                    const f = (tt) => { tt = (tt + 1) % 1; return tt < 1 / 6 ? p2 + (q - p2) * 6 * tt : tt < 0.5 ? q : tt < 2 / 3 ? p2 + (q - p2) * (2 / 3 - tt) * 6 : p2; };
                    px[i] = f(h + 1 / 3) * 255; px[i + 1] = f(h) * 255; px[i + 2] = f(h - 1 / 3) * 255;
                }
                cx.putImageData(d, 0, 0);
                return c;
            } catch (e) { return null; }
        }
        // índices 3..6: Spark rojo, Spark azul, Spark verde y Renault rojo
        sprites[1].addEventListener('load', () => {
            [[-55, 0], [160, 0], [55, 0]].forEach(([gr, sa], k) => { const v = repintar(sprites[1], gr, sa); if (v) sprites[3 + k] = v; });
        });
        sprites[0].addEventListener('load', () => { const v = repintar(sprites[0], 2, 0.8); if (v) sprites[6] = v; });
        const listo = (im) => im && (im instanceof HTMLCanvasElement || (im.complete && im.naturalWidth));

        let SW = 0, SH = 0, vertical = false, L = 0, A = 0;
        let carriles = 3, anchoCarril = 24, anchoVia = 72, yVia = 0, tam = 26;
        let camX = 0, ultimo = 0, autos = [], activo = true, t = 0;
        const SEG = 44;            // largo de cada tramo de decoración
        const META_CADA = 820;     // distancia entre líneas de meta

        function hash(i, k) {
            let h = (i * 374761393 + (k || 0) * 668265263) | 0;
            h = (h ^ (h >>> 13)) * 1274126177 | 0;
            return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
        }

        let pxCss = 3, camDraw = 0;
        function preparar() {
            const cw = window.innerWidth, ch = window.innerHeight;
            // Cada "pixel" del juego ocupa un número ENTERO de píxeles físicos de la pantalla:
            // así no hay píxeles de distinto tamaño que tiemblen al moverse.
            const dpr = window.devicePixelRatio || 1;
            const objetivo = cw < 600 ? 2 : (cw < 1200 ? 3 : 4);
            pxCss = Math.max(1, Math.round(objetivo * dpr)) / dpr;
            SW = Math.ceil(cw / pxCss);
            SH = Math.ceil(ch / pxCss);
            vertical = ch > cw;
            // una columna/fila extra para mover el lienzo una fracción de pixel (desplazamiento suave)
            canvas.width = SW + (vertical ? 0 : 1);
            canvas.height = SH + (vertical ? 1 : 0);
            canvas.style.width = (canvas.width * pxCss) + 'px';
            canvas.style.height = (canvas.height * pxCss) + 'px';
            canvas.style.right = 'auto';
            canvas.style.bottom = 'auto';
            canvas.style.left = '0px';
            canvas.style.top = vertical ? (-pxCss) + 'px' : '0px';
            L = vertical ? canvas.height : canvas.width;
            A = vertical ? SW : SH;
            carriles = vertical ? 3 : 4;
            anchoCarril = Math.max(16, Math.min(Math.floor((A * 0.62) / carriles), vertical ? 28 : 34));
            anchoVia = anchoCarril * carriles;
            yVia = Math.round((A - anchoVia) / 2);
            tam = Math.round(anchoCarril * 1.25);
            escala = Math.max(1, Math.min(2, (yVia - 4) / 30));
            iniciarAutos();
        }

        function centroCarril(c) { return c * anchoCarril + anchoCarril / 2; }
        function velocidadCamara() { return 46 * sceneSpeed; }

        function nuevoAuto(enPantalla) {
            const vc = velocidadCamara();
            // Todos van más rápido que la cámara: siempre avanzan hacia adelante y se adelantan entre ellos
            const v = vc * (1.35 + Math.random() * 1.05);
            let x;
            if (enPantalla) x = camX + Math.random() * L;
            else x = camX - tam * 1.5 - Math.random() * L * 0.5;
            const carril = Math.floor(Math.random() * carriles);
            return { x, carril, y: centroCarril(carril), v, vBase: v, sprite: Math.floor(Math.random() * 7) };
        }

        function iniciarAutos() {
            const n = vertical ? 5 : 8;
            autos = [];
            for (let i = 0; i < n; i++) {
                let a, intentos = 0;
                do { a = nuevoAuto(true); intentos++; }
                while (intentos < 20 && autos.some(o => o.carril === a.carril && Math.abs(o.x - a.x) < tam * 1.8));
                autos.push(a);
            }
        }

        function libre(carril, x, ignorar) {
            return !autos.some(o => o !== ignorar && Math.abs(o.y - centroCarril(carril)) < anchoCarril * 0.7 && Math.abs(o.x - x) < tam * 1.9);
        }

        function moverAutos(dt) {
            const s = dt / 1000;
            autos.forEach(a => {
                // ¿hay alguien más lento justo delante en mi carril?
                const delante = autos.filter(o => o !== a && o.carril === a.carril && o.x > a.x && o.x - a.x < tam * 2.2)
                    .sort((p, q) => p.x - q.x)[0];
                if (delante && delante.v < a.v) {
                    const opciones = [a.carril - 1, a.carril + 1].filter(c => c >= 0 && c < carriles).sort(() => Math.random() - 0.5);
                    const nuevo = opciones.find(c => libre(c, a.x, a));
                    if (nuevo !== undefined) a.carril = nuevo;          // adelantamiento
                    else a.v = Math.min(a.v, delante.v);               // frena detrás
                } else if (a.v < a.vBase) {
                    a.v = Math.min(a.vBase, a.v + a.vBase * s * 0.6);   // vuelve a acelerar
                }
                a.x += a.v * s;
                a.y += (centroCarril(a.carril) - a.y) * Math.min(1, s * 3.5);
            });
            // los que salen de la pantalla se reemplazan
            const margen = L * 0.6 + tam * 3;
            autos = autos.map(a => (a.x < camX - margen || a.x > camX + L + margen) ? nuevoAuto(false) : a);
        }

        // ---------- piezas pixel art ----------
        function rect(x, y, w, h, c) { g.fillStyle = c; g.fillRect(Math.round(x), Math.round(y), w, h); }

        let escala = 1;   // 1 en celular, hasta 2 en PC
        function palmera(x, y) {
            const hojas = [[0, -1], [1, -1], [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1]];
            const largo = Math.round(5 * escala), grosor = escala > 1.4 ? 3 : 2;
            rect(x + 3, y + 3, largo, largo, 'rgba(0,0,0,0.12)');
            hojas.forEach(([dx, dy], i) => {
                for (let k = 1; k <= largo; k++) rect(x + dx * k, y + dy * k, grosor, grosor, k > largo * 0.65 ? '#1d7a36' : (i % 2 ? '#2f9e47' : '#3cb554'));
            });
            rect(x - 1, y - 1, 3 + (grosor - 2), 3 + (grosor - 2), '#7a4e22');
            rect(x + 1, y + 1, 1, 1, '#c98a3c');
        }
        function arbol(x, y) {
            const r = Math.round(4 * escala + 1);
            rect(x - r + 3, y - r + 4, r * 2, r * 2 - 1, 'rgba(0,0,0,0.15)');
            rect(x - r, y - r + 1, r * 2, r * 2 - 2, '#1f7a36');
            rect(x - r + 1, y - r, r * 2 - 2, r * 2, '#1f7a36');
            rect(x - r + 1, y - r + 1, r * 2 - 3, r * 2 - 3, '#2c9645');
            rect(x - r + 2, y - r + 2, Math.max(2, r - 1), Math.max(2, r - 1), '#43b45b');
        }
        // Vallas publicitarias continuas junto a los pianos
        function vallas(x0, y, alto) {
            const colores = ['#ffffff', '#1f4fd1', '#ffd200', '#ffffff', '#e8394a'];
            const largo = 18;
            const k0 = Math.floor((camDraw + x0) / largo);
            for (let k = k0; k * largo - camDraw < L; k++) {
                const x = k * largo - camDraw;
                const c = colores[((k % colores.length) + colores.length) % colores.length];
                rect(x, y, largo - 1, alto, c);
                if (c === '#ffffff' && alto >= 5) textoPixel(g, 'ECI', x + 3, y + Math.floor((alto - 5) / 2), '#e8394a');
                else if (alto >= 5) rect(x + 3, y + Math.floor(alto / 2), largo - 7, 1, c === '#ffd200' ? '#0b1636' : '#ffffff');
            }
        }
        function casa(x, y, w, h) {
            rect(x - 1, y - 1, w + 2, h + 2, '#efe7d6');
            rect(x, y, w, h, '#c8553d');
            for (let k = 1; k < h; k += 2) rect(x, y + k, w, 1, '#a9432f');
            rect(x, y + Math.floor(h / 2), w, 1, '#7d2f21');
        }
        function arbusto(x, y, r) {
            rect(x - r, y - r + 1, r * 2, r * 2 - 2, '#2a8c40');
            rect(x - r + 1, y - r, r * 2 - 2, r * 2, '#2a8c40');
            rect(x - r + 2, y - r + 1, 2, 2, '#45b85e');
        }
        function bandera(x, y, fase) {
            const ola = Math.sin(t * 0.008 + fase) > 0 ? 1 : 0;
            rect(x, y, 1, 6, '#5a3a1a');
            rect(x + 1, y + ola, 4, 2, '#ffd200');
            rect(x + 1, y + 2 + ola, 4, 1, '#1f4fd1');
            rect(x + 1, y + 3 + ola, 4, 1, '#e11d2a');
        }
        function hinchas(x, y, cuantos, semilla, haciaArriba) {
            const ropa = ['#e11d2a', '#ffd200', '#1f4fd1', '#ffffff', '#2de3c6', '#ff7a1a'];
            for (let k = 0; k < cuantos; k++) {
                const px = x + k * 4;
                const salto = Math.sin(t * 0.01 + semilla * 10 + k) > 0.6 ? -1 : 0;
                rect(px, y + 2 + salto, 3, 2, ropa[Math.floor(hash(semilla, k) * ropa.length)]);
                rect(px + 1, y + 1 + salto, 1, 1, '#ffd2a1');
                if (hash(semilla, k + 9) > 0.55) bandera(px + 1, haciaArriba ? y - 6 + salto : y + salto, k + semilla);
            }
        }
        function grada(x, y, w, h, semilla) {
            rect(x, y, w, h, '#c9d2e3');
            const publico = ['#e11d2a', '#ffd200', '#1f4fd1', '#ffffff', '#2de3c6', '#8a5cff'];
            for (let fy = y + 1; fy < y + h - 1; fy += 2) {
                for (let fx = x + 1; fx < x + w - 1; fx += 2) {
                    const brilla = Math.sin(t * 0.006 + fx * 0.7 + fy) > 0.85;
                    rect(fx, fy, 1, 1, brilla ? '#ffffff' : publico[Math.floor(hash(fx + semilla * 31, fy) * publico.length)]);
                }
            }
            rect(x, y, w, 1, '#e8394a');
            // pancarta ECI
            if (w >= 18) {
                const bx = x + Math.floor((w - 15) / 2);
                const by = y + Math.floor((h - 7) / 2);
                rect(bx, by, 15, 7, '#ffffff');
                rect(bx, by, 15, 1, '#0b1636');
                textoPixel(g, 'ECI', bx + 2, by + 1, '#e8394a');
            }
        }

        function decorarLado(x, y0, alto, semilla, ladoArriba) {
            const r = hash(semilla, ladoArriba ? 1 : 2);
            const cercaVia = ladoArriba ? y0 + alto - 7 : y0 + 1;
            if (r < 0.28) {
                hinchas(x + 2, cercaVia, Math.min(9, Math.floor(SEG / 4.5)), semilla, ladoArriba);
                if (alto > 20) palmera(x + SEG * 0.6, ladoArriba ? y0 + alto * 0.35 : y0 + alto * 0.65);
            } else if (r < 0.42 && alto >= 16) {
                const gh = Math.min(16, alto - 4);
                grada(x + 1, ladoArriba ? y0 + alto - gh - 2 : y0 + 2, SEG - 2, gh, semilla);
            } else if (r < 0.62) {
                palmera(x + SEG * 0.3, y0 + alto * (0.3 + hash(semilla, 5) * 0.4));
                if (alto > 24) arbol(x + SEG * 0.75, y0 + alto * (0.25 + hash(semilla, 6) * 0.5));
            } else if (r < 0.8 && alto >= 18) {
                const cw = 12 + Math.floor(hash(semilla, 7) * 8), chh = 9 + Math.floor(hash(semilla, 8) * 5);
                const cy = ladoArriba ? y0 + 3 : y0 + alto - chh - 3;
                casa(x + 6, Math.max(y0 + 1, cy), cw, Math.min(chh, alto - 4));
                arbusto(x + SEG - 8, y0 + alto / 2, 3);
            } else {
                arbol(x + 10, y0 + alto * 0.45);
                arbusto(x + SEG * 0.6, y0 + alto * 0.6, 3);
                if (alto > 20) hinchas(x + 20, cercaVia, 4, semilla + 3, ladoArriba);
            }
        }

        function dibujar() {
            camDraw = Math.floor(camX);
            g.setTransform(1, 0, 0, 1, 0, 0);
            g.imageSmoothingEnabled = false;
            if (vertical) { g.translate(0, canvas.height); g.rotate(-Math.PI / 2); }

            // césped con franjas de corte
            const franja = 16;
            const k0 = Math.floor(camDraw / franja);
            for (let k = k0; k * franja - camDraw < L; k++) {
                rect(k * franja - camDraw, 0, franja, A, k % 2 ? '#3aa94a' : '#34a044');
            }

            // decoración a ambos lados (dejando sitio para las vallas)
            const valla = yVia > 30 ? 6 : 4;
            const bordeArriba = yVia - 4 - valla - 1, bordeAbajo = yVia + anchoVia + 4 + valla + 1;
            const i0 = Math.floor(camDraw / SEG) - 1;
            for (let i = i0; i * SEG - camDraw < L + SEG; i++) {
                const x = i * SEG - camDraw;
                decorarLado(x, 0, bordeArriba, i, true);
                decorarLado(x, bordeAbajo, A - bordeAbajo, i + 5000, false);
            }

            vallas(0, yVia - 4 - valla, valla);
            vallas(9, yVia + anchoVia + 4, valla);

            // pianos (curbs) rojo/blanco
            const bloque = 6;
            const b0 = Math.floor(camDraw / bloque);
            for (let b = b0; b * bloque - camDraw < L; b++) {
                const c = b % 2 ? '#ff3344' : '#ffffff';
                rect(b * bloque - camDraw, yVia - 4, bloque, 4, c);
                rect(b * bloque - camDraw, yVia + anchoVia, bloque, 4, c);
            }

            // asfalto
            rect(0, yVia, L, anchoVia, '#555e6e');
            for (let i = i0; i * SEG - camDraw < L + SEG; i++) {
                for (let k = 0; k < 6; k++) {
                    rect(i * SEG - camDraw + hash(i, k + 20) * SEG, yVia + hash(i, k + 40) * anchoVia, 1, 1, hash(i, k) > 0.5 ? '#4b5362' : '#626b7c');
                }
            }
            rect(0, yVia + 1, L, 1, '#f4f6fb');
            rect(0, yVia + anchoVia - 2, L, 1, '#f4f6fb');
            // líneas de carril discontinuas
            for (let c = 1; c < carriles; c++) {
                const y = yVia + c * anchoCarril;
                const d0 = Math.floor(camDraw / 20);
                for (let d = d0; d * 20 - camDraw < L; d++) rect(d * 20 - camDraw, y, 10, 1, '#f4f6fb');
            }
            // línea de meta a cuadros con banderas de Colombia en los postes
            const m0 = Math.floor(camDraw / META_CADA);
            for (let m = m0; m * META_CADA - camDraw < L + 20; m++) {
                const mx = m * META_CADA - camDraw + L * 0.5;
                if (mx < -12 || mx > L + 12) continue;
                for (let cy = 0; cy < anchoVia; cy += 2) {
                    for (let cx = 0; cx < 6; cx += 2) {
                        rect(mx + cx, yVia + cy, 2, 2, ((cx + cy) / 2) % 2 ? '#0b1636' : '#ffffff');
                    }
                }
                bandera(mx + 2, yVia - 12, 1);
                bandera(mx + 2, yVia + anchoVia + 6, 2);
            }

            // autos
            autos.slice().sort((p, q) => p.x - q.x).forEach(a => {
                const sx = a.x - camDraw, sy = yVia + a.y;
                if (sx < -tam || sx > L + tam) return;
                g.save();
                g.translate(Math.round(sx), Math.round(sy));
                g.fillStyle = 'rgba(0,0,0,0.22)';
                g.fillRect(-Math.round(tam * 0.34) + 2, -Math.round(tam * 0.17) + 2, Math.round(tam * 0.68), Math.round(tam * 0.34));
                g.rotate(Math.PI / 2);
                const im = sprites[a.sprite] || sprites[a.sprite % 3];
                if (listo(im)) g.drawImage(im, -tam / 2, -tam / 2, tam, tam);
                else { g.fillStyle = COLORES_RESPALDO[a.sprite]; g.fillRect(-tam * 0.25, -tam * 0.42, tam * 0.5, tam * 0.84); }
                g.restore();
            });
            const frac = (camX - camDraw) * pxCss;
            canvas.style.transform = vertical ? `translate3d(0, ${frac.toFixed(2)}px, 0)` : `translate3d(${(-frac).toFixed(2)}px, 0, 0)`;
        }

        function loop(now) {
            if (!activo) return;
            if (document.hidden) {
                ultimo = 0;
            } else {
                const dt = ultimo ? Math.min(50, now - ultimo) : 16;
                ultimo = now;
                t += dt;
                camX += velocidadCamara() * dt / 1000;
                moverAutos(dt);
                dibujar();
            }
            requestAnimationFrame(loop);
        }

        preparar();
        dibujar();
        sprites.forEach(im => im.addEventListener('load', () => { if (reduce) dibujar(); }));
        let rz = null;
        window.addEventListener('resize', () => {
            clearTimeout(rz);
            rz = setTimeout(() => { preparar(); dibujar(); }, 150);
        });
        if (!reduce) requestAnimationFrame(loop);

        return {
            stop() { activo = false; },
            start() { if (!activo) { activo = true; ultimo = 0; if (!reduce) requestAnimationFrame(loop); } }
        };
    };

    // Elige el fondo según los ajustes: pista animada (por defecto) o ilustración
    let pista = null;
    GameUI.applyBackground = function () {
        const canvas = document.getElementById('scene');
        const usarPista = GameUI.settings.trackBg;
        document.documentElement.classList.toggle('bg-pista', usarPista);
        if (!canvas) return;
        canvas.style.display = usarPista ? 'block' : 'none';
        if (usarPista) {
            if (!pista) pista = GameUI.track(canvas);
            else pista.start();
        } else if (pista) {
            pista.stop();
        }
    };

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------
    GameUI.fillIcons(document);
    if (document.getElementById('scene')) GameUI.applyBackground();

    window.GameUI = GameUI;
})();
