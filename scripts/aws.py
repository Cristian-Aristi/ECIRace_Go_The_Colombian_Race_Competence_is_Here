"""ECIRace GO en AWS (Sprint 8). Ejecutar desde la raiz del proyecto. Python 3.10+ y AWS CLI v2.

Pensado para AWS Academy Learner Lab: antes de usarlo, inicie el laboratorio (Start Lab) y copie
las credenciales de "AWS Details > AWS CLI" en ~/.aws/credentials. Caducan con cada sesion.

Comandos:
  check     verifica credenciales y region
  deploy    crea o actualiza la infraestructura (deploy/aws/cloudformation.yaml) y espera a que responda
  up        enciende la instancia (y con ella la ultima imagen publicada) para clase o demo
  down      detiene la instancia: no se cobra computo, solo el disco y la IP elastica
  status    estado de la instancia, URL y salud del servidor
  release   vuelve a descargar la imagen y reinicia los contenedores (via SSM)
  logs      ultimas lineas de los contenedores, o del arranque con: logs bootstrap (via SSM)
  monitor   up|down  Prometheus + Grafana locales leyendo las metricas de la instancia
  k6        carga|estres|pico|duracion  pruebas de proyecto.js contra la URL publica
  destroy   elimina toda la infraestructura (incluida la base de datos)
"""
import argparse, base64, datetime, gzip, json, os, pathlib, secrets, shutil, socket, string, subprocess, sys, time
import urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEPLOY = ROOT / 'deploy' / 'aws'
CFG_FILE = DEPLOY / '.aws-deploy.json'   # local y en .gitignore: guarda la clave de metricas
AWS = shutil.which('aws') or 'aws'
COMPOSE_CLOUD = ['docker', 'compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.cloud.yml']
AMI_PARAM = '/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64'
TAGS = ['proyecto=ECIRace GO', 'curso=ARSW', 'sprint=8']


# ------------------------------------------------------------------ utilidades
def load_cfg():
    return json.loads(CFG_FILE.read_text(encoding='utf-8')) if CFG_FILE.exists() else {}


def save_cfg(cfg):
    CFG_FILE.write_text(json.dumps(cfg, indent=2), encoding='utf-8')
    try:
        CFG_FILE.chmod(0o600)
    except OSError:
        pass


def _shown(arg):
    """Oculta la clave de metricas y resume los archivos empaquetados al imprimir comandos."""
    key, _, value = arg.partition('=')
    if key == 'MetricsPassword':
        return 'MetricsPassword=***'
    if key in ('ComposeFile', 'Caddyfile', 'BootstrapScript'):
        return f'{key}=<{len(value)} B gzip+base64>'
    return arg


def aws(cfg, *args, check=True, quiet=False):
    """Ejecuta AWS CLI y devuelve la salida JSON (o None si no hay salida)."""
    cmd = [AWS, *args, '--region', cfg['region'], '--output', 'json']
    if not quiet:
        print('  $ aws', ' '.join(_shown(a) for a in args))
    r = subprocess.run(cmd, text=True, capture_output=True)
    if r.returncode != 0:
        err = r.stderr.strip()
        if 'ExpiredToken' in err or 'InvalidClientTokenId' in err or 'Unable to locate credentials' in err:
            raise SystemExit('Las credenciales de AWS caducaron o no existen.\n'
                             'En Learner Lab: Start Lab > AWS Details > AWS CLI > Show, y copie el bloque en '
                             '~/.aws/credentials (en Windows: %UserProfile%\\.aws\\credentials).')
        if check:
            raise SystemExit(f'Fallo aws {" ".join(args[:2])}:\n{err}')
        return None
    out = r.stdout.strip()
    if not out:
        return {}  # exito sin salida (p. ej. los comandos "wait")
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return {'text': out}  # "cloudformation deploy" responde con texto, no JSON


def need_credentials(cfg):
    ident = aws(cfg, 'sts', 'get-caller-identity', quiet=True)
    print(f'Cuenta AWS: {ident["Account"]}  ({ident["Arn"].split("/")[-1]})  region: {cfg["region"]}')
    return ident


def packed(name):
    # mtime=0: el mismo archivo produce siempre el mismo parametro, asi un deploy sin cambios
    # no modifica el UserData (y no reinicia la instancia)
    data = (DEPLOY / name).read_bytes().replace(b'\r\n', b'\n')
    return base64.b64encode(gzip.compress(data, compresslevel=9, mtime=0)).decode()


def new_metrics_password():
    alphabet = string.ascii_letters + string.digits
    return ''.join(secrets.choice(alphabet) for _ in range(32))


def stack(cfg):
    stacks = aws(cfg, 'cloudformation', 'describe-stacks', '--stack-name', cfg['stack'], check=False, quiet=True)
    return stacks['Stacks'][0] if stacks else None


def stack_ok(cfg):
    s = stack(cfg)
    return bool(s) and s['StackStatus'] in ('CREATE_COMPLETE', 'UPDATE_COMPLETE')


def print_failures(cfg):
    """Muestra los recursos que fallaron y el motivo que da CloudFormation."""
    events = aws(cfg, 'cloudformation', 'describe-stack-events', '--stack-name', cfg['stack'],
                 check=False, quiet=True) or {'StackEvents': []}
    failed = [e for e in events['StackEvents'] if e['ResourceStatus'].endswith('FAILED')]
    print('\nRecursos que fallaron (del mas reciente al mas antiguo):')
    for e in failed[:10]:
        print(f'  - {e["LogicalResourceId"]}: {e.get("ResourceStatusReason", "sin detalle")}')


def outputs(cfg):
    s = stack(cfg)
    if not s or 'Outputs' not in s:
        raise SystemExit(f'No existe el stack {cfg["stack"]}. Ejecute primero: python scripts/aws.py deploy')
    return {o['OutputKey']: o['OutputValue'] for o in s['Outputs']}


def instance_state(cfg, instance_id):
    r = aws(cfg, 'ec2', 'describe-instances', '--instance-ids', instance_id, quiet=True)
    return r['Reservations'][0]['Instances'][0]['State']['Name']


def wait_healthy(url, seconds=600):
    print(f'Esperando a {url}/actuator/health (primer arranque: Docker, imagenes y certificado, ~3-5 min)...')
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(f'{url}/actuator/health', timeout=10) as r:
                if r.status == 200:
                    print('Servidor UP:', r.read(200).decode())
                    return True
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
            pass
        time.sleep(10)
    print('El servidor no respondio a tiempo. Revise: python scripts/aws.py logs')
    return False


def default_network(cfg, instance_type):
    vpcs = aws(cfg, 'ec2', 'describe-vpcs', '--filters', 'Name=isDefault,Values=true', quiet=True)['Vpcs']
    if not vpcs:
        raise SystemExit('La cuenta no tiene VPC por defecto.')
    vpc = vpcs[0]['VpcId']
    offered = {o['Location'] for o in aws(
        cfg, 'ec2', 'describe-instance-type-offerings', '--location-type', 'availability-zone',
        '--filters', f'Name=instance-type,Values={instance_type}', quiet=True)['InstanceTypeOfferings']}
    subnets = aws(cfg, 'ec2', 'describe-subnets', '--filters', f'Name=vpc-id,Values={vpc}',
                  'Name=default-for-az,Values=true', quiet=True)['Subnets']
    usable = sorted((s for s in subnets if s['AvailabilityZone'] in offered), key=lambda s: s['AvailabilityZone'])
    if not usable:
        raise SystemExit(f'Ninguna zona de la VPC por defecto ofrece {instance_type}.')
    return vpc, usable[0]['SubnetId'], usable[0]['AvailabilityZone']


def check_dns(cfg, domain):
    """El dominio propio debe apuntar a la IP elastica antes de desplegar: Let's Encrypt lo valida."""
    s = stack(cfg)
    ip = next((o['OutputValue'] for o in (s or {}).get('Outputs', []) if o['OutputKey'] == 'PublicIp'), None)
    if not ip:
        print(f'AVISO: aun no hay IP elastica; despues del primer deploy cree el registro A de {domain} '
              'y vuelva a ejecutar deploy --domain.')
        return
    try:
        resolved = socket.gethostbyname(domain)
    except OSError:
        resolved = None
    if resolved != ip:
        raise SystemExit(f'{domain} apunta a {resolved or "nada"} y deberia apuntar a {ip}.\n'
                         f'Cree o corrija el registro A en su proveedor de DNS (en DuckDNS: campo "current ip" = {ip}), '
                         'espere un par de minutos y repita el deploy.')
    print(f'DNS correcto: {domain} -> {ip}')


def ssm_run(cfg, commands, timeout=600):
    """Ejecuta comandos en la instancia con SSM Run Command y devuelve la salida."""
    instance = outputs(cfg)['InstanceId']
    sent = aws(cfg, 'ssm', 'send-command', '--instance-ids', instance, '--document-name', 'AWS-RunShellScript',
               '--comment', 'ECIRace GO scripts/aws.py',
               '--parameters', json.dumps({'commands': commands, 'executionTimeout': [str(timeout)]}), check=False)
    if not sent:
        raise SystemExit('SSM no esta disponible para la instancia. Alternativa: python scripts/aws.py down y luego up '
                         '(al encender se descarga la ultima imagen).')
    cid = sent['Command']['CommandId']
    end = time.time() + timeout + 60
    while time.time() < end:
        time.sleep(5)
        r = aws(cfg, 'ssm', 'get-command-invocation', '--command-id', cid, '--instance-id', instance,
                check=False, quiet=True)
        if r and r['Status'] not in ('Pending', 'InProgress', 'Delayed'):
            if r['Status'] != 'Success':
                print(r.get('StandardErrorContent', ''))
                raise SystemExit(f'El comando termino en estado {r["Status"]}.')
            return r.get('StandardOutputContent', '')
    raise SystemExit('Tiempo agotado esperando el comando SSM.')


# ------------------------------------------------------------------ comandos
def cmd_check(a, cfg):
    need_credentials(cfg)
    s = stack(cfg)
    print(f'Stack {cfg["stack"]}: {s["StackStatus"] if s else "no existe"}')


def cmd_deploy(a, cfg):
    need_credentials(cfg)
    if a.image:
        cfg['image'] = a.image
    if a.instance_type:
        cfg['instance_type'] = a.instance_type
    if a.domain is not None:  # --domain "" vuelve a usar solo la URL de sslip.io
        cfg['domain'] = a.domain.strip().lower()
    if cfg.get('domain'):
        check_dns(cfg, cfg['domain'])
    cfg.setdefault('image', 'ghcr.io/cristian-aristi/ecirace-go:latest')
    cfg.setdefault('instance_type', 't3.small')
    cfg.setdefault('metrics_password', new_metrics_password())
    if 'ami' not in cfg:  # se fija una vez: una AMI nueva reemplazaria la instancia y sus datos
        cfg['ami'] = aws(cfg, 'ssm', 'get-parameter', '--name', AMI_PARAM, quiet=True)['Parameter']['Value']
    if 'subnet' not in cfg:
        cfg['vpc'], cfg['subnet'], az = default_network(cfg, cfg['instance_type'])
        print(f'Red: {cfg["vpc"]} / {cfg["subnet"]} ({az})')
    save_cfg(cfg)

    s = stack(cfg)
    if s and s['StackStatus'] == 'ROLLBACK_COMPLETE':
        print('El primer intento fallo y quedo en ROLLBACK_COMPLETE; se elimina para reintentar...')
        aws(cfg, 'cloudformation', 'delete-stack', '--stack-name', cfg['stack'])
        aws(cfg, 'cloudformation', 'wait', 'stack-delete-complete', '--stack-name', cfg['stack'])

    params = [f'VpcId={cfg["vpc"]}', f'SubnetId={cfg["subnet"]}', f'AmiId={cfg["ami"]}',
              f'InstanceType={cfg["instance_type"]}', f'AppImage={cfg["image"]}',
              f'MetricsPassword={cfg["metrics_password"]}',
              f'ComposeFile={packed("docker-compose.yml")}', f'Caddyfile={packed("Caddyfile")}',
              f'BootstrapScript={packed("bootstrap.sh")}', f'SshCidr={a.ssh_cidr or ""}',
              f'KeyName={a.key_name or ""}', f'InstanceProfileName={a.instance_profile}',
              f'CustomDomain={cfg.get("domain", "")}']
    print('Desplegando deploy/aws/cloudformation.yaml (3-5 min la primera vez)...')
    ok = aws(cfg, 'cloudformation', 'deploy', '--stack-name', cfg['stack'],
             '--template-file', str(DEPLOY / 'cloudformation.yaml'), '--no-fail-on-empty-changeset',
             '--parameter-overrides', *params, '--tags', *TAGS, check=False)
    if ok is None or not stack_ok(cfg):
        print_failures(cfg)
        raise SystemExit('El despliegue fallo. Corrija la causa de arriba y vuelva a ejecutar deploy.')
    out = outputs(cfg)
    cfg.update(url=out['Url'], instance=out['InstanceId'])
    save_cfg(cfg)
    print(f'\nURL: {out["Url"]}')
    if out.get('SslipUrl') and out['SslipUrl'] != out['Url']:
        print(f'Respaldo: {out["SslipUrl"]}')
    if instance_state(cfg, out['InstanceId']) == 'running':
        wait_healthy(out['Url'])
    print('Al terminar de trabajar: python scripts/aws.py down')


def cmd_up(a, cfg):
    need_credentials(cfg)
    out = outputs(cfg)
    if instance_state(cfg, out['InstanceId']) != 'running':
        aws(cfg, 'ec2', 'start-instances', '--instance-ids', out['InstanceId'])
        aws(cfg, 'ec2', 'wait', 'instance-running', '--instance-ids', out['InstanceId'])
    wait_healthy(out['Url'])
    print(f'\nEstado: UP. Jugadores: {out["Url"]}\nAl terminar: python scripts/aws.py down')


def cmd_down(a, cfg):
    need_credentials(cfg)
    out = outputs(cfg)
    if instance_state(cfg, out['InstanceId']) not in ('stopped', 'stopping'):
        aws(cfg, 'ec2', 'stop-instances', '--instance-ids', out['InstanceId'])
    aws(cfg, 'ec2', 'wait', 'instance-stopped', '--instance-ids', out['InstanceId'])
    print('\nEstado: DOWN. Sin costo de computo; los datos y la URL se conservan.')


def cmd_status(a, cfg):
    need_credentials(cfg)
    s = stack(cfg)
    if not s:
        raise SystemExit(f'No existe el stack {cfg["stack"]}. Ejecute deploy.')
    out = outputs(cfg)
    state = instance_state(cfg, out['InstanceId'])
    health = 'no aplica (instancia detenida)'
    if state == 'running':
        try:
            with urllib.request.urlopen(f'{out["Url"]}/actuator/health', timeout=10) as r:
                health = r.read(200).decode()
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            health = f'sin respuesta ({e})'
    print(f'Stack     : {cfg["stack"]} -> {s["StackStatus"]}')
    print(f'Instancia : {out["InstanceId"]} ({cfg.get("instance_type", "?")}) -> {state}')
    print(f'URL       : {out["Url"]}')
    if out.get('SslipUrl') and out['SslipUrl'] != out['Url']:
        print(f'Respaldo  : {out["SslipUrl"]}')
    print(f'Salud     : {health}')
    print('Creditos  : pantalla del Learner Lab (Budget). Recuerde: down al terminar.')


def cmd_release(a, cfg):
    need_credentials(cfg)
    print('Descargando la ultima imagen y reiniciando contenedores...')
    print(ssm_run(cfg, ['cd /opt/ecirace && bash bootstrap.sh 2>&1 | tail -n 15']))
    wait_healthy(outputs(cfg)['Url'], seconds=300)


def cmd_logs(a, cfg):
    need_credentials(cfg)
    if a.service == 'bootstrap':  # arranque de la instancia: Docker, imagenes, certificado
        cmd = (f'tail -n {a.tail} /var/log/ecirace-bootstrap.log 2>/dev/null || echo "(sin log de bootstrap)"; '
               'echo "--- cloud-init ---"; grep -iE "error|fail|scripts-user" /var/log/cloud-init-output.log | tail -n 30')
    else:
        cmd = f'cd /opt/ecirace && docker compose ps -a && docker compose logs --no-color --tail {a.tail} {a.service or ""}'
    print(ssm_run(cfg, [cmd], timeout=120))


def cmd_monitor(a, cfg):
    if a.mode == 'down':
        subprocess.run([*COMPOSE_CLOUD, 'rm', '-sf', 'probe', 'prometheus', 'grafana', 'k6-results'], cwd=ROOT)
        print('Monitoreo detenido.')
        return
    if 'metrics_password' not in cfg:
        raise SystemExit('Falta la clave de metricas: copie deploy/aws/.aws-deploy.json de quien hizo el deploy.')
    url = cfg.get('url') or outputs(cfg)['Url']
    host = url.removeprefix('https://')
    target = ROOT / 'monitoring' / 'prometheus-cloud.yml'
    target.write_text(
        'global:\n  scrape_interval: 15s\nscrape_configs:\n'
        '  - job_name: ecirace\n    scheme: https\n    metrics_path: /actuator/prometheus\n'
        f'    basic_auth:\n      username: metrics\n      password: {cfg["metrics_password"]}\n'
        f'    static_configs:\n      - targets: [\'{host}\']\n'
        '  - job_name: probe\n    static_configs:\n      - targets: [\'probe:9101\']\n'
        '  - job_name: k6-results\n    static_configs:\n      - targets: [\'k6-results:9102\']\n', encoding='utf-8')
    subprocess.run([*COMPOSE_CLOUD, 'up', '-d', '--build', 'probe', 'prometheus', 'grafana', 'k6-results'],
                   cwd=ROOT, check=True, env={**os.environ, 'ECIRACE_CLOUD_URL': url})
    print('Grafana: http://localhost:3000 (mismos tableros, ahora con datos de AWS).\n'
          'Al terminar: python scripts/aws.py monitor down')


def cmd_k6(a, cfg):
    url = cfg.get('url') or outputs(cfg)['Url']
    name = f'aws-{a.profile}'
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run = f'{name}-{stamp}'
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    extra = ['-o', 'experimental-prometheus-rw', '--tag', f'testid={run}'] if a.live else []
    (ROOT / 'results').mkdir(exist_ok=True)
    r = subprocess.run([*COMPOSE_CLOUD, '--profile', 'test', 'run', '--rm', '-e', f'PROFILE={a.profile}',
                        '-e', f'RUN_ID={run}', '-e', f'BASE_URL={url}', 'k6', 'run', *extra, '/scripts/proyecto.js'],
                       cwd=ROOT)
    end = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (ROOT / 'results' / f'{run}-run.json').write_text(json.dumps(
        {'profile': name, 'start_utc': start, 'end_utc': end, 'exit_code': r.returncode}, indent=2))
    print('Umbrales incumplidos: analizar evidencia.' if r.returncode == 99 else f'k6 termino con codigo {r.returncode}.')


def cmd_destroy(a, cfg):
    need_credentials(cfg)
    if input(f'Esto BORRA la instancia, la IP y la base de datos. Escriba "{cfg["stack"]}" para confirmar: ').strip() \
            != cfg['stack']:
        raise SystemExit('Cancelado.')
    aws(cfg, 'cloudformation', 'delete-stack', '--stack-name', cfg['stack'])
    aws(cfg, 'cloudformation', 'wait', 'stack-delete-complete', '--stack-name', cfg['stack'])
    for k in ('url', 'instance', 'ami', 'vpc', 'subnet'):
        cfg.pop(k, None)
    save_cfg(cfg)
    print('Infraestructura eliminada.')


def main():
    cfg = load_cfg()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--region', help='region (por defecto us-east-1, la de Learner Lab)')
    p.add_argument('--stack', help='nombre del stack (por defecto ecirace)')
    sub = p.add_subparsers(dest='cmd', required=True)
    d = sub.add_parser('deploy')
    d.add_argument('--image', help='imagen (por defecto ghcr.io/cristian-aristi/ecirace-go:latest)')
    d.add_argument('--instance-type', choices=['t3.small', 't3.medium', 't2.small', 't2.medium'])
    d.add_argument('--ssh-cidr', help='abrir SSH solo para este rango, p. ej. 190.0.0.1/32')
    d.add_argument('--key-name', help='par de llaves para SSH (en Learner Lab: vockey)')
    d.add_argument('--instance-profile', default='LabInstanceProfile', help='perfil IAM existente ("" = ninguno)')
    d.add_argument('--domain', help='dominio propio con registro A hacia la IP elastica ("" = solo sslip.io)')
    for name in ('check', 'up', 'down', 'status', 'release', 'destroy'):
        sub.add_parser(name)
    lg = sub.add_parser('logs')
    lg.add_argument('service', nargs='?', choices=['app', 'caddy', 'postgres', 'bootstrap'])
    lg.add_argument('--tail', type=int, default=100)
    m = sub.add_parser('monitor')
    m.add_argument('mode', choices=['up', 'down'])
    k = sub.add_parser('k6')
    k.add_argument('profile', choices=['carga', 'estres', 'pico', 'duracion'])
    k.add_argument('--live', action='store_true', help='enviar metricas en vivo a Prometheus (requiere monitor up)')
    a = p.parse_args()
    cfg['region'] = a.region or cfg.get('region') or 'us-east-1'
    cfg['stack'] = a.stack or cfg.get('stack') or 'ecirace'
    globals()[f'cmd_{a.cmd}'](a, cfg)


if __name__ == '__main__':
    sys.exit(main())
