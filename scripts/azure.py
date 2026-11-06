"""ECIRace GO en Azure (Sprint 8). Ejecutar desde la raiz del proyecto. Python 3.10+ y Azure CLI (az login).

Estados del despliegue (lo que cuesta cada uno, ver azure/README.md):
  up       demo/clase: Postgres encendido, 1 replica siempre caliente, sin arranque en frio
  standby  desarrollo: Postgres encendido, la app escala a cero y despierta con la primera visita (~30-60 s)
  down     apagado total: sin ingress (nadie puede despertarla), 0 replicas y Postgres detenido

Comandos:
  deploy   crea o actualiza toda la infraestructura (azure/main.bicep) y deja el estado en standby
  up | standby | down | status
  release  cambia la imagen (nueva revision), p. ej. despues de que GitHub Actions publique una nueva
  logs     sigue los logs de la consola del contenedor
  monitor  up|down  Prometheus + Grafana locales leyendo las metricas de la app en Azure
  k6       carga|estres|pico|duracion  pruebas de proyecto.js contra la URL de Azure
  destroy  borra el grupo de recursos completo (deja de consumir por completo)
"""
import argparse, datetime, json, os, pathlib, secrets, shutil, subprocess, sys, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CFG_FILE = ROOT / 'azure' / '.azure.json'          # local, en .gitignore (contiene la clave de Postgres)
APP = 'ecirace-app'
AZ = shutil.which('az') or shutil.which('az.cmd') or 'az'
COMPOSE_AZ = ['docker', 'compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.azure.yml']
TAGS = ['proyecto=ECIRace GO', 'curso=ARSW', 'sprint=8']
PROVIDERS = ['Microsoft.App', 'Microsoft.OperationalInsights', 'Microsoft.DBforPostgreSQL', 'Microsoft.Consumption']


# ------------------------------------------------------------------ utilidades
def load_cfg():
    return json.loads(CFG_FILE.read_text(encoding='utf-8')) if CFG_FILE.exists() else {}


def save_cfg(cfg):
    CFG_FILE.parent.mkdir(exist_ok=True)
    CFG_FILE.write_text(json.dumps(cfg, indent=2), encoding='utf-8')


def az(*args, check=True, quiet=False):
    """Ejecuta az y devuelve la salida JSON (o None)."""
    cmd = [AZ, *args, '--only-show-errors', '-o', 'json']
    if not quiet:
        print('  $ az', ' '.join(x.split('=')[0] + '=***' if 'password' in x.lower() and '=' in x else x
                                for x in args))
    r = subprocess.run(cmd, text=True, capture_output=True)
    if r.returncode != 0:
        if check:
            raise SystemExit(f'Fallo az {args[0]} {args[1] if len(args) > 1 else ""}:\n{r.stderr.strip()}')
        return None
    out = r.stdout.strip()
    return json.loads(out) if out else None


def need_login():
    acc = az('account', 'show', check=False, quiet=True)
    if not acc:
        raise SystemExit('No hay sesion de Azure CLI. Ejecute: az login   (y si tiene varias: az account set -s "Azure for Students")')
    print(f'Suscripcion: {acc["name"]} ({acc["id"]})')
    return acc


def pg_server(rg):
    servers = az('postgres', 'flexible-server', 'list', '-g', rg, check=False, quiet=True) or []
    return servers[0] if servers else None


def pg_start(rg):
    s = pg_server(rg)
    if s and s.get('state') == 'Stopped':
        print(f'Encendiendo PostgreSQL {s["name"]} (tarda 1-3 min)...')
        az('postgres', 'flexible-server', 'start', '-g', rg, '-n', s['name'])
    return s


def pg_stop(rg):
    s = pg_server(rg)
    if s and s.get('state') == 'Ready':
        print(f'Deteniendo PostgreSQL {s["name"]} (Azure lo vuelve a encender solo a los 7 dias)...')
        az('postgres', 'flexible-server', 'stop', '-g', rg, '-n', s['name'])
    return s


def app_show(rg):
    return az('containerapp', 'show', '-g', rg, '-n', APP, check=False, quiet=True)


def ingress(rg, enabled):
    current = (app_show(rg) or {}).get('properties', {}).get('configuration', {}).get('ingress')
    if enabled and not current:
        az('containerapp', 'ingress', 'enable', '-g', rg, '-n', APP,
           '--type', 'external', '--target-port', '8080', '--transport', 'auto')
    elif not enabled and current:
        az('containerapp', 'ingress', 'disable', '-g', rg, '-n', APP)


def replicas(rg, minimum):
    scale = (app_show(rg) or {}).get('properties', {}).get('template', {}).get('scale', {})
    if scale.get('minReplicas', 0) == minimum and scale.get('maxReplicas') == 1:
        return  # evita crear una revision nueva si ya esta asi
    az('containerapp', 'update', '-g', rg, '-n', APP, '--min-replicas', str(minimum), '--max-replicas', '1')


def url(cfg):
    if not cfg.get('fqdn'):  # p. ej. en GitHub Actions no existe azure/.azure.json
        ing = (app_show(cfg['rg']) or {}).get('properties', {}).get('configuration', {}).get('ingress') or {}
        cfg['fqdn'] = ing.get('fqdn')
    if not cfg.get('fqdn'):
        raise SystemExit('No hay URL guardada. Ejecute primero: python scripts/azure.py deploy')
    return f'https://{cfg["fqdn"]}'


def wait_healthy(base, seconds=300):
    print(f'Esperando a {base}/actuator/health (la primera vez la JVM tarda)...')
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(f'{base}/actuator/health', timeout=20) as r:
                if r.status == 200:
                    print('Servidor UP:', r.read(200).decode())
                    return True
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(5)
    print('El servidor no respondio a tiempo. Revise: python scripts/azure.py logs')
    return False


def new_password():
    # 4 categorias (mayuscula, minuscula, digito, simbolo) como exige PostgreSQL en Azure
    return 'Ec1!' + secrets.token_hex(12)


# ------------------------------------------------------------------ comandos
def cmd_deploy(a, cfg):
    need_login()
    cfg.update({k: v for k, v in {
        'rg': a.rg, 'location': a.location, 'db_location': a.db_location, 'image': a.image,
        'budget': a.budget, 'emails': a.email, 'postgres': (not a.no_postgres) if a.no_postgres else None,
    }.items() if v not in (None, [])})
    cfg.setdefault('location', 'eastus2')
    cfg.setdefault('image', 'ghcr.io/cristian-aristi/ecirace-go:latest')
    cfg.setdefault('postgres', True)
    cfg.setdefault('budget', 15)
    cfg.setdefault('db_password', new_password())
    save_cfg(cfg)
    rg = cfg['rg']

    print('Registrando proveedores de recursos (solo tarda la primera vez)...')
    for p in PROVIDERS:
        az('provider', 'register', '-n', p, '--wait', quiet=True, check=False)

    az('group', 'create', '-n', rg, '-l', cfg['location'], '--tags', *TAGS)
    pg_start(rg)  # un servidor detenido no acepta cambios de la plantilla

    params = [f'location={cfg["location"]}', f'dbLocation={cfg.get("db_location") or cfg["location"]}',
              f'image={cfg["image"]}', f'deployPostgres={str(cfg["postgres"]).lower()}',
              f'dbPassword={cfg["db_password"]}', f'minReplicas={1 if a.warm else 0}']
    if cfg.get('registry_server'):
        params += [f'registryServer={cfg["registry_server"]}', f'registryUser={cfg["registry_user"]}',
                   f'registryPassword={cfg["registry_password"]}']
    print('Desplegando azure/main.bicep (5-10 min la primera vez por PostgreSQL)...')
    dep = az('deployment', 'group', 'create', '-g', rg, '-n', 'ecirace-main',
             '-f', str(ROOT / 'azure' / 'main.bicep'), '-p', *params)
    out = {k: v['value'] for k, v in dep['properties']['outputs'].items()}
    cfg.update(fqdn=out['fqdn'], pg=out['pgServerName'])
    save_cfg(cfg)

    if cfg.get('emails'):
        print('Creando presupuesto mensual con alertas por correo...')
        ok = az('deployment', 'group', 'create', '-g', rg, '-n', 'ecirace-budget',
                '-f', str(ROOT / 'azure' / 'budget.bicep'),
                '-p', f'amount={cfg["budget"]}', f'contactEmails={json.dumps(cfg["emails"])}', check=False)
        if not ok:
            print('AVISO: la suscripcion no permitio crear el presupuesto por CLI. '
                  'Creelo en el portal: Grupo de recursos > Presupuestos (Budgets).')
    else:
        print('AVISO: sin --email no se crea el presupuesto con alertas.')

    print(f'\nListo: {url(cfg)}\nEstado: {"up" if a.warm else "standby"}. '
          'Al terminar de trabajar: python scripts/azure.py down')
    if a.warm:
        wait_healthy(url(cfg))


def cmd_up(a, cfg):
    need_login()
    rg = cfg['rg']
    pg_start(rg)
    ingress(rg, True)
    replicas(rg, 1)
    wait_healthy(url(cfg))
    print(f'\nEstado: UP (demo). Jugadores: {url(cfg)}\nAl terminar: python scripts/azure.py down')


def cmd_standby(a, cfg):
    need_login()
    rg = cfg['rg']
    pg_start(rg)
    ingress(rg, True)
    replicas(rg, 0)
    print(f'\nEstado: STANDBY. La app duerme y despierta con la primera visita a {url(cfg)}')


def cmd_down(a, cfg):
    need_login()
    rg = cfg['rg']
    if not app_show(rg):
        raise SystemExit(f'No existe {APP} en {rg}.')
    ingress(rg, False)
    replicas(rg, 0)
    pg_stop(rg)
    print('\nEstado: DOWN. Solo se cobra el almacenamiento de Postgres (32 GB) y nada de computo.')


def cmd_status(a, cfg):
    need_login()
    rg = cfg['rg']
    ca = app_show(rg)
    if not ca:
        raise SystemExit(f'No existe {APP} en {rg}. Ejecute deploy.')
    props = ca['properties']
    scale = props['template']['scale']
    ing = props['configuration'].get('ingress')
    reps = az('containerapp', 'replica', 'list', '-g', rg, '-n', APP, check=False, quiet=True) or []
    s = pg_server(rg)
    print(f'Container App : {APP}  imagen={props["template"]["containers"][0]["image"]}')
    print(f'  ingress     : {"https://" + ing["fqdn"] if ing else "DESHABILITADO"}')
    print(f'  replicas    : {len(reps)} corriendo (min={scale.get("minReplicas", 0)}, max={scale.get("maxReplicas")})')
    print(f'PostgreSQL    : {s["name"] + " -> " + s["state"] if s else "no desplegado (H2 en memoria)"}')
    estado = ('DOWN' if not ing else 'UP' if scale.get('minReplicas', 0) >= 1 else 'STANDBY')
    print(f'Estado        : {estado}')
    print('Creditos      : https://www.microsoftazuresponsorships.com/Balance')
    print('Costos        : Portal > Grupo de recursos > Analisis de costos')


def cmd_release(a, cfg):
    need_login()
    cfg['image'] = a.image
    save_cfg(cfg)
    az('containerapp', 'update', '-g', cfg['rg'], '-n', APP, '--image', a.image)
    print(f'Nueva revision con {a.image}')


def cmd_logs(a, cfg):
    subprocess.run([AZ, 'containerapp', 'logs', 'show', '-g', cfg['rg'], '-n', APP, '--follow', '--tail', '100'])


def cmd_monitor(a, cfg):
    if a.mode == 'down':
        subprocess.run([*COMPOSE_AZ, 'rm', '-sf', 'probe', 'prometheus', 'grafana', 'k6-results'], cwd=ROOT)
        print('Monitoreo detenido: la app ya puede volver a dormir.')
        return
    fqdn = url(cfg).removeprefix('https://')
    (ROOT / 'monitoring' / 'prometheus-azure.yml').write_text(
        'global:\n  scrape_interval: 15s\nscrape_configs:\n'
        '  - job_name: ecirace\n    scheme: https\n    metrics_path: /actuator/prometheus\n'
        f'    static_configs:\n      - targets: [\'{fqdn}\']\n'
        '  - job_name: probe\n    static_configs:\n      - targets: [\'probe:9101\']\n'
        '  - job_name: k6-results\n    static_configs:\n      - targets: [\'k6-results:9102\']\n', encoding='utf-8')
    env = {'ECIRACE_AZURE_URL': url(cfg)}
    subprocess.run([*COMPOSE_AZ, 'up', '-d', '--build', 'probe', 'prometheus', 'grafana', 'k6-results'],
                   cwd=ROOT, check=True, env={**os.environ, **env})
    print('Grafana: http://localhost:3000 (mismos tableros, ahora con datos de Azure).\n'
          'AVISO: mientras el monitoreo corre la app no duerme. Al terminar: python scripts/azure.py monitor down')


def cmd_k6(a, cfg):
    base = url(cfg)
    name = f'azure-{a.profile}'
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run = f'{name}-{stamp}'
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    extra = ['-o', 'experimental-prometheus-rw', '--tag', f'testid={run}'] if a.live else []
    (ROOT / 'results').mkdir(exist_ok=True)
    r = subprocess.run([*COMPOSE_AZ, '--profile', 'test', 'run', '--rm', '-e', f'PROFILE={a.profile}',
                        '-e', f'RUN_ID={run}', '-e', f'BASE_URL={base}', 'k6', 'run', *extra, '/scripts/proyecto.js'],
                       cwd=ROOT)
    end = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (ROOT / 'results' / f'{run}-run.json').write_text(json.dumps(
        {'profile': name, 'start_utc': start, 'end_utc': end, 'exit_code': r.returncode}, indent=2))
    print('Umbrales incumplidos: analizar evidencia.' if r.returncode == 99 else f'k6 termino con codigo {r.returncode}.')


def cmd_destroy(a, cfg):
    need_login()
    rg = cfg['rg']
    if input(f'Esto BORRA todo (incluida la base de datos). Escriba "{rg}" para confirmar: ').strip() != rg:
        raise SystemExit('Cancelado.')
    az('group', 'delete', '-n', rg, '--yes', '--no-wait')
    cfg.pop('fqdn', None)
    save_cfg(cfg)
    print('Borrado en curso (unos minutos). Verifique en el portal que el grupo desaparezca.')


def main():
    cfg = load_cfg()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--rg', default=None, help='grupo de recursos (por defecto rg-ecirace-arsw)')
    sub = p.add_subparsers(dest='cmd', required=True)
    d = sub.add_parser('deploy')
    d.add_argument('--location', help='region (por defecto eastus2)')
    d.add_argument('--db-location', help='region de Postgres si difiere')
    d.add_argument('--image', help='imagen (por defecto ghcr.io/cristian-aristi/ecirace-go:latest)')
    d.add_argument('--email', action='append', default=[], help='correo para alertas del presupuesto (repetible)')
    d.add_argument('--budget', type=int, help='presupuesto mensual USD (por defecto 15)')
    d.add_argument('--no-postgres', action='store_true', help='usar H2 en memoria (sin base de datos administrada)')
    d.add_argument('--warm', action='store_true', help='dejarla en estado up al terminar')
    for name in ('up', 'standby', 'down', 'status', 'logs', 'destroy'):
        sub.add_parser(name)
    r = sub.add_parser('release')
    r.add_argument('image', help='p. ej. ghcr.io/cristian-aristi/ecirace-go:sha-abc1234')
    m = sub.add_parser('monitor')
    m.add_argument('mode', choices=['up', 'down'])
    k = sub.add_parser('k6')
    k.add_argument('profile', choices=['carga', 'estres', 'pico', 'duracion'])
    k.add_argument('--live', action='store_true', help='enviar metricas en vivo a Prometheus (requiere monitor up)')
    a = p.parse_args()
    a.rg = a.rg or cfg.get('rg') or 'rg-ecirace-arsw'
    cfg['rg'] = a.rg
    globals()[f'cmd_{a.cmd}'](a, cfg)


if __name__ == '__main__':
    sys.exit(main())