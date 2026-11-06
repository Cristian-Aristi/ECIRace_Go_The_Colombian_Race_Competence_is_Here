import http from 'k6/http';
import { check } from 'k6';
// Mismos perfiles, umbrales y resumen que k6/tests.js del laboratorio.
// Cambia solo el objetivo: el perfil de un piloto de ECIRace (consulta JPA real sobre H2),
// equivalente a /api/boards/1 (consulta SQLite real) del laboratorio.
const profile = __ENV.PROFILE || 'carga';
const BASE = __ENV.BASE_URL || 'http://ecirace-server:8080';
const PILOT = __ENV.PILOT || 'Daniel';
const common = {timeUnit:'1s', preAllocatedVUs:30, maxVUs:60, gracefulStop:'5s'};
const profiles = {
 carga:{...common,executor:'constant-arrival-rate',rate:20,duration:'1m'},
 estres:{...common,executor:'ramping-arrival-rate',startRate:20,stages:[{target:20,duration:'20s'},{target:60,duration:'30s'},{target:120,duration:'30s'},{target:20,duration:'40s'}]},
 pico:{...common,executor:'ramping-arrival-rate',startRate:20,stages:[{target:20,duration:'20s'},{target:160,duration:'5s'},{target:160,duration:'15s'},{target:20,duration:'5s'},{target:20,duration:'45s'}]},
 duracion:{...common,executor:'constant-arrival-rate',rate:20,duration:'4m'}
};
if (!profiles[profile]) throw new Error('Perfil desconocido');
export const options={scenarios:{[profile]:profiles[profile]},summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.01'],checks:['rate>0.99']}};
export default function(){
 const r=http.get(`${BASE}/api/auth/profile/${PILOT}`,{timeout:'2s',tags:{name:'perfil_piloto'}});
 check(r,{'HTTP 200 y piloto correcto':r=>{try{return r.status===200 && r.json('username')===PILOT;}catch(e){return false;}}});
}
export function handleSummary(data){
 return {[`/results/${__ENV.RUN_ID || profile}.json`]:JSON.stringify({profile,run_id:__ENV.RUN_ID,...data},null,2),stdout:JSON.stringify({profile,metrics:data.metrics},null,2)+'\n'};
}
