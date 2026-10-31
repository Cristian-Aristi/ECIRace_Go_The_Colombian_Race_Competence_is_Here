import os,threading,time,urllib.request
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
lock=threading.Lock(); attempts=successes=last=0
url=os.getenv('PROBE_URL','http://app:8000/ready')
def loop():
 global attempts,successes,last
 while True:
  try:
   with urllib.request.urlopen(url,timeout=1) as r:ok=int(r.status==200)
  except Exception:ok=0
  with lock:attempts+=1;successes+=ok;last=ok
  time.sleep(2)
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  if self.path!='/metrics':self.send_error(404);return
  with lock:body=f'lab_probe_success {last}\nlab_probe_attempts_total {attempts}\nlab_probe_successes_total {successes}\n'.encode()
  self.send_response(200);self.send_header('Content-Type','text/plain; version=0.0.4');self.end_headers();self.wfile.write(body)
if __name__=='__main__':
 threading.Thread(target=loop,daemon=True).start();ThreadingHTTPServer(('0.0.0.0',9101),Handler).serve_forever()
