"""Ejecutar desde la raiz del proyecto ECIRace GO (donde esta pom.xml). Python 3.10+; Docker Compose v2.
Adaptacion de scripts/lab.py del laboratorio ARSW: misma interfaz, apuntando a ECIRace (puerto 8080).
Comandos extra: real (quita la simulacion) y proyecto <perfil> (pruebas de todo ECIRace con k6)."""
import argparse,datetime,json,pathlib,subprocess,time,urllib.error,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
APP='http://localhost:8080'
TARGET=f'{APP}/api/auth/profile/Daniel'
SERVICES=['ecirace-server','probe','prometheus','grafana','k6-results']
JOBS={'ecirace','probe','k6-results'}
def compose(*args,check=True):return subprocess.run(['docker','compose',*args],cwd=ROOT,check=check)
def get(url):
 with urllib.request.urlopen(url,timeout=5) as r:return r.read()
def config(**kwargs):
 req=urllib.request.Request(f'{APP}/admin/config',json.dumps(kwargs).encode(),{'Content-Type':'application/json','X-Lab-Token':'arsw-local'})
 try:print(urllib.request.urlopen(req,timeout=5).read().decode())
 except urllib.error.HTTPError as e:
  raise SystemExit(f'/admin/config respondio {e.code}. Verifique ECIRACE_LAB_ENABLED=true en docker-compose.yml y reconstruya: python scripts/lab.py up') from e
def test(profile,script='tests.js',prefix='',live=False):
 name=f'{prefix}{profile}'
 stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ');run=f'{name}-{stamp}'
 start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 # live=True envia ademas las metricas de k6 en vivo a Prometheus (tablero "ECIRace GO Proyecto")
 extra=['-o','experimental-prometheus-rw','--tag',f'testid={run}'] if live else []
 r=compose('run','--rm','-e',f'PROFILE={profile}','-e',f'RUN_ID={run}','k6','run',*extra,f'/scripts/{script}',check=False)
 end=datetime.datetime.now(datetime.timezone.utc).isoformat()
 (ROOT/'results'/f'{run}-run.json').write_text(json.dumps({'profile':name,'start_utc':start,'end_utc':end,'exit_code':r.returncode},indent=2))
 if r.returncode not in (0,99):raise SystemExit(f'Error de ejecucion k6: {r.returncode}; revise logs')
 print('Umbrales incumplidos: analizar evidencia.' if r.returncode==99 else 'Umbrales cumplidos.')
p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','up','doctor','sample','fault','reset','improve','real','test','proyecto','down']);p.add_argument('profile',nargs='?',choices=['carga','estres','pico','duracion','all']);a=p.parse_args()
(ROOT/'results').mkdir(exist_ok=True)
if a.action=='prepare':compose('--profile','test','pull','--ignore-buildable');compose('build')
elif a.action=='up':compose('up','-d','--build',*SERVICES)
elif a.action=='doctor':
 compose('config','--quiet');compose('ps')
 for url in [TARGET,f'{APP}/lab/ready',f'{APP}/actuator/prometheus','http://localhost:9090/-/ready','http://localhost:3000/api/health']:
  print(url,get(url)[:180].decode())
 targets=json.loads(get('http://localhost:9090/api/v1/targets'))['data']['activeTargets']
 health={t['labels']['job']:t['health'] for t in targets}
 assert set(health)==JOBS and all(h=='up' for h in health.values()),f'Targets {health}. ECIRace tarda en arrancar: espere 30 s y vuelva a ejecutar doctor'
 print('OK: los tres targets UP. Ejecute la prueba carga y compruebe Grafana antes de clase.')
elif a.action=='sample':
 for _ in range(100):get(TARGET);time.sleep(.05)
elif a.action=='fault':
 try:config(ready=False);print('Falla de negocio durante 30 s; /actuator/prometheus sigue disponible.');time.sleep(30)
 finally:config(ready=True)
elif a.action=='reset':config(ready=True,delay_ms=120,capacity=8)
elif a.action=='improve':config(ready=True,delay_ms=60,capacity=8)
elif a.action=='real':config(ready=True,delay_ms=0,capacity=0)
elif a.action=='test':
 if not a.profile:p.error('test requiere carga, estres, pico, duracion o all')
 for profile in (['carga','estres','pico','duracion'] if a.profile=='all' else [a.profile]):test(profile)
elif a.action=='proyecto':
 # Todo ECIRace (API REST + carreras por WebSocket) sin simulacion de laboratorio
 if not a.profile:p.error('proyecto requiere carga, estres, pico, duracion o all')
 config(ready=True,delay_ms=0,capacity=0)
 for profile in (['carga','estres','pico','duracion'] if a.profile=='all' else [a.profile]):test(profile,'proyecto.js','proyecto-',live=True)
elif a.action=='down':compose('down')
