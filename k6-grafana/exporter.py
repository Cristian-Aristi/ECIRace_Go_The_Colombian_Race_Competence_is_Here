"""Read-only bridge: k6 handleSummary JSON -> Prometheus gauges, per run.
Local teaching use. One series per metric/run: archive old JSON when done.
No historical samples or production throughput are fabricated.
"""
import json, math, os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
RESULTS=Path(os.environ.get('RESULTS_DIR','/results'))
FIELDS={
 'p95_ms':('http_req_duration','p(95)'),
 'p99_ms':('http_req_duration','p(99)'),
 'mean_ms':('http_req_duration','avg'),
 'requests_per_second':('http_reqs','rate'),
 'requests':('http_reqs','count'),
 'error_ratio':('http_req_failed','rate'),
 'checks_ratio':('checks','rate'),
 'dropped_iterations':('dropped_iterations','count'),
}
def label(s):return str(s).replace('\\','\\\\').replace('\n','\\n').replace('"','\\"')
def finite(v):return isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v)
def collect(directory=RESULTS):
 rows={name:[] for name in [*FIELDS,'thresholds_ok','duration_seconds','finished_timestamp_seconds','exit_code']}
 invalid=0;loaded=0
 for path in sorted(directory.glob('*.json')):
  if path.name.endswith('-run.json'):continue
  try:
   doc=json.loads(path.read_text(encoding='utf-8'))
   if not isinstance(doc,dict) or not isinstance(doc.get('metrics'),dict):raise ValueError('not a summary')
   metrics=doc['metrics']
   if not isinstance(metrics.get('http_reqs',{}).get('values'),dict):raise ValueError('missing http_reqs')
   profile=doc.get('profile','desconocido')
   # Filename is authoritative and unique even when callers reuse run_id.
   tags=f'profile="{label(profile)}",run="{label(path.stem)}"'
   extracted={}
   for name,(metric,key) in FIELDS.items():
    value=metrics.get(metric,{}).get('values',{}).get(key)
    if name=='dropped_iterations' and metric not in metrics:value=0
    if finite(value):extracted[name]=value
   flags=[t.get('ok') for m in metrics.values() for t in m.get('thresholds',{}).values() if isinstance(t,dict)]
   if flags and all(type(f) is bool for f in flags):extracted['thresholds_ok']=int(all(flags))
   duration=doc.get('state',{}).get('testRunDurationMs')
   if finite(duration):extracted['duration_seconds']=duration/1000
  except (OSError,ValueError,TypeError,AttributeError):
   invalid+=1;continue # A partially-written file will be retried next scrape.
  # Sidecar is optional. An unfinished or malformed sidecar does not erase a valid summary.
  sidecar=path.with_name(path.stem+'-run.json')
  if sidecar.exists():
   try:
    from datetime import datetime
    meta=json.loads(sidecar.read_text(encoding='utf-8'))
    if finite(meta.get('exit_code')):extracted['exit_code']=meta['exit_code']
    if meta.get('end_utc'):extracted['finished_timestamp_seconds']=datetime.fromisoformat(meta['end_utc'].replace('Z','+00:00')).timestamp()
   except (OSError,ValueError,TypeError,AttributeError):invalid+=1
  for name,v in extracted.items():rows[name].append(f'lab_k6_summary_{name}{{{tags}}} {v}')
  loaded+=1
 out=[]
 for name,values in rows.items():
  if values:out.extend([f'# TYPE lab_k6_summary_{name} gauge',*values])
 out.extend(['# TYPE lab_k6_summary_files gauge',f'lab_k6_summary_files {loaded}','# TYPE lab_k6_summary_invalid_files gauge',f'lab_k6_summary_invalid_files {invalid}'])
 return '\n'.join(out)+'\n'
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  if self.path not in ('/metrics','/health'):self.send_error(404);return
  body=(collect() if self.path=='/metrics' else 'ok\n').encode()
  self.send_response(200);self.send_header('Content-Type','text/plain; version=0.0.4');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',9102),Handler).serve_forever()
