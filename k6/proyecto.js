import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
// Pruebas de TODO ECIRace GO con los mismos 4 perfiles, umbrales y resumen JSON del laboratorio.
// Dos escenarios en paralelo:
//   api      -> recorre la API REST como lo hace el front: login, perfil, salas, unirse, iniciar
//               y posiciones (todo con el token de sesión, como el navegador).
//   carreras -> cada usuario virtual juega una carrera completa por WebSocket/STOMP: crea su sala,
//               se une, la inicia, envía telemetría GPS cada segundo a 9 km/h y espera a que el
//               servidor detecte que cruzó la meta.
// Se ejecuta con: python scripts/lab.py proyecto carga|estres|pico|duracion|all
const profile = __ENV.PROFILE || 'carga';
const BASE = __ENV.BASE_URL || 'http://ecirace-server:8080';
const WS_URL = BASE.replace(/^http/, 'ws') + '/race-ws-native';
const RACE_SECONDS = Number(__ENV.RACE_SECONDS || 30);   // tiempo máximo de cada carrera
const START = { lat: 4.7828, lon: -74.0430 };            // campus ECI
const SPEED_MPS = 2.5;                                   // 9 km/h: el anti-cheat rechaza > 30 km/h
const META_METROS = 50;                                  // la meta queda 50 m adelante (radio de llegada: 15 m)
const M_PER_DEG_LON = 110930;                            // metros por grado de longitud en el campus

// Métricas propias. La latencia se guarda como número en ms (sin marcarla como tiempo) para que llegue
// a Prometheus siempre en ms, sin conversiones de unidad.
const e2e = new Trend('ecirace_e2e_latency_ms');
const sent = new Counter('ecirace_telemetry_sent');
const echoed = new Counter('ecirace_telemetry_echoed');
const finished = new Rate('ecirace_race_finished');
const setupFailed = new Rate('ecirace_setup_failed');

const api = { exec: 'apiFlow', timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 80, gracefulStop: '10s' };
const races = { exec: 'raceFlow', gracefulStop: `${RACE_SECONDS + 15}s` };
const profiles = {
  carga: {
    api: { ...api, executor: 'constant-arrival-rate', rate: 5, duration: '1m' },
    carreras: { ...races, executor: 'constant-vus', vus: 20, duration: '1m' },
  },
  estres: {
    api: { ...api, executor: 'ramping-arrival-rate', startRate: 5, stages: [{ target: 5, duration: '20s' }, { target: 15, duration: '30s' }, { target: 30, duration: '30s' }, { target: 5, duration: '40s' }] },
    carreras: { ...races, executor: 'ramping-vus', startVUs: 20, gracefulRampDown: `${RACE_SECONDS + 15}s`, stages: [{ target: 20, duration: '20s' }, { target: 60, duration: '30s' }, { target: 120, duration: '30s' }, { target: 20, duration: '40s' }] },
  },
  pico: {
    api: { ...api, executor: 'ramping-arrival-rate', startRate: 5, stages: [{ target: 5, duration: '20s' }, { target: 40, duration: '5s' }, { target: 40, duration: '15s' }, { target: 5, duration: '5s' }, { target: 5, duration: '45s' }] },
    carreras: { ...races, executor: 'ramping-vus', startVUs: 20, gracefulRampDown: `${RACE_SECONDS + 15}s`, stages: [{ target: 20, duration: '20s' }, { target: 150, duration: '5s' }, { target: 150, duration: '15s' }, { target: 20, duration: '5s' }, { target: 20, duration: '45s' }] },
  },
  duracion: {
    api: { ...api, executor: 'constant-arrival-rate', rate: 5, duration: '4m' },
    carreras: { ...races, executor: 'constant-vus', vus: 20, duration: '4m' },
  },
};
if (!profiles[profile]) throw new Error('Perfil desconocido');

export const options = {
  scenarios: profiles[profile],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  // Sin la etiqueta "url": cada sala tiene una URL distinta y llenaría Prometheus de series
  systemTags: ['status', 'method', 'name', 'scenario', 'check', 'error', 'error_code', 'expected_response'],
  thresholds: {
    // Objetivos del laboratorio
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    // Objetivos del tiempo real
    ecirace_e2e_latency_ms: ['p(95)<300'],
    ecirace_race_finished: ['rate>0.95'],
    ws_connecting: ['p(95)<1000'],
  },
};

const headers = (token) => Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: `Bearer ${token}` } : {});
const post = (path, body, name, token) =>
  http.post(`${BASE}${path}`, JSON.stringify(body), { headers: headers(token), tags: { name }, timeout: '5s' });
const get = (path, name, token) => http.get(`${BASE}${path}`, { headers: headers(token), tags: { name }, timeout: '5s' });
// Máximo 20 caracteres (regla del servidor para códigos de sala)
const uniqueCode = (prefix) => `${prefix}-${__VU}-${__ITER % 10000}-${Date.now() % 100000}`;

// Un piloto invitado por usuario virtual y por ejecución: los tags ya registrados no se pueden reclamar,
// así que llevan un sufijo aleatorio (máx. 16 caracteres). El token se reutiliza en cada iteración.
const RUN_TAG = Math.random().toString(36).slice(2, 7);
let session = null;
function login(prefix) {
  if (session) return session;
  const user = `${prefix}${__VU}_${RUN_TAG}`.slice(0, 16);
  const res = post('/api/auth/guest', { username: user }, 'login_invitado');
  const ok = check(res, { 'login 201': (r) => r.status === 201 && r.json('profile.username') === user });
  session = ok ? { user, token: res.json('token') } : null;
  return session;
}

// Crea una sala propia (código explícito), se une y la inicia como host (token de sesión).
function prepareRace(s, prefix, metaLat, metaLon) {
  const created = post('/api/rooms/create', { preferredCode: uniqueCode(prefix) }, 'crear_sala', s.token);
  const room = created.status === 201 ? created.json('room.roomCode') : null;
  const joined = room ? post(`/api/rooms/${room}/join`, {}, 'unirse_sala', s.token) : null;
  const started = room ? post(`/api/rooms/${room}/start`, { metaLat, metaLon }, 'iniciar_carrera', s.token) : null;
  const ok = check(null, {
    'sala creada (201)': () => created.status === 201,
    'unido a la sala (200)': () => !!joined && joined.status === 200,
    'carrera iniciada (200)': () => !!started && started.status === 200,
  });
  setupFailed.add(!ok);
  return ok ? room : null;
}

// ---------------- Escenario 1: API REST ----------------
export function apiFlow() {
  const s = login('k6a');
  if (!s) {
    sleep(1);
    return;
  }

  check(get('/api/auth/me', 'perfil_piloto', s.token), { 'perfil 200': (r) => r.status === 200 });

  const list = get('/api/rooms', 'listar_salas');
  check(list, { 'salas 200': (r) => r.status === 200 });

  const room = prepareRace(s, 'KA', 4.80, -74.00); // meta lejana: esta sala no recibe telemetría
  if (room) {
    check(get(`/api/rooms/${room}`, 'consultar_sala', s.token), { 'sala 200': (r) => r.status === 200 });
    check(get(`/api/rooms/${room}/positions`, 'posiciones_sala', s.token), { 'posiciones 200': (r) => r.status === 200 });
  }
}

// ---------------- Escenario 2: carrera completa por WebSocket ----------------
function frame(command, headers, body = '') {
  return `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\u0000`;
}

function parseFrame(raw) {
  const text = String(raw);
  const sep = text.indexOf('\n\n');
  const head = (sep === -1 ? text : text.slice(0, sep)).split('\n');
  const headers = {};
  head.slice(1).forEach((line) => {
    const i = line.indexOf(':');
    if (i > 0) headers[line.slice(0, i)] = line.slice(i + 1);
  });
  return { command: head[0].trim(), headers, body: sep === -1 ? '' : text.slice(sep + 2).replace(/\u0000+$/, '') };
}

export function raceFlow() {
  const s = login('k6r');
  if (!s) {
    sleep(1);
    return;
  }
  const user = s.user;

  const room = prepareRace(s, 'KR', START.lat, START.lon + META_METROS / M_PER_DEG_LON);
  if (!room) {
    sleep(1);
    return;
  }

  let won = false;
  let stompError = false;
  const res = ws.connect(WS_URL, { tags: { name: 'carrera_ws' } }, (socket) => {
    // El token de sesión autentica la conexión STOMP: el servidor fija al corredor (ignora runnerId)
    socket.on('open', () => socket.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', Authorization: `Bearer ${s.token}` })));

    socket.on('message', (raw) => {
      const f = parseFrame(raw);
      if (f.command === 'CONNECTED') {
        socket.send(frame('SUBSCRIBE', { id: 'sala', destination: `/topic/rooms/${room}` }));
        socket.send(frame('SUBSCRIBE', { id: 'estado', destination: `/topic/rooms/${room}/status` }));
        const t0 = Date.now();
        socket.setInterval(() => {
          // Posición calculada por tiempo transcurrido: la velocidad es siempre 9 km/h aunque k6 se retrase
          const now = Date.now();
          const lon = START.lon + (SPEED_MPS * (now - t0) / 1000) / M_PER_DEG_LON;
          const packet = { roomCode: room, latitude: START.lat, longitude: lon, timestamp: now };
          socket.send(frame('SEND', { destination: '/app/telemetry', 'content-type': 'application/json' }, JSON.stringify(packet)));
          sent.add(1);
        }, 1000);
        socket.setTimeout(() => socket.close(), RACE_SECONDS * 1000);
        return;
      }
      if (f.command === 'ERROR') {
        stompError = true;
        return;
      }
      if (f.command !== 'MESSAGE') return;

      let body;
      try { body = JSON.parse(f.body); } catch (e) { return; }
      const dest = f.headers.destination || '';
      if (dest === `/topic/rooms/${room}` && body.runnerId === user && body.timestamp) {
        e2e.add(Date.now() - body.timestamp); // ida y vuelta: el timestamp lo puso este mismo cliente
        echoed.add(1);
      } else if (dest === `/topic/rooms/${room}/status` && body.status === 'FINISHED') {
        won = body.winnerId === user;
        socket.close();
      }
    });
  });

  check(res, { 'WebSocket conectado (101)': (r) => r && r.status === 101 });
  check(null, {
    'sin errores STOMP': () => !stompError,
    'el servidor detectó la llegada a la meta': () => won,
  });
  finished.add(won);

  // El resultado lo guarda el propio servidor al detectar la meta (ya no existe /api/auth/race-result)
  sleep(1);
}

export function handleSummary(data) {
  const name = `proyecto-${profile}`;
  return {
    [`/results/${__ENV.RUN_ID || name}.json`]: JSON.stringify({ profile: name, run_id: __ENV.RUN_ID, ...data }, null, 2),
    stdout: JSON.stringify({ profile: name, metrics: data.metrics }, null, 2) + '\n',
  };
}
