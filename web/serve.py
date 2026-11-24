#!/usr/bin/env python3
"""错题集本地服务：静态文件 + AI 请求代理。

浏览器直连大模型 API 会被跨域（CORS）拦掉——DeepSeek、智谱都不返回
Access-Control-Allow-Origin。所以让页面把请求发给本机这个服务，由它转发。
只监听 127.0.0.1，不对外网开放；Key 仍然是浏览器发过来的，服务不保存。
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
TIMEOUT = 180
ALLOW_HOSTS = (
    'https://api.deepseek.com',
    'https://open.bigmodel.cn',
    'https://dashscope.aliyuncs.com',
    'https://api.siliconflow.cn',
    'https://api.openai.com',
    'https://api.moonshot.cn',
    'https://ark.cn-beijing.volces.com',
    'https://api.minimax.chat',
)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        if '/proxy' in (self.path or ''):
            sys.stderr.write('[proxy] %s\n' % (fmt % args))
        else:
            pass

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'authorization,content-type')
        self.send_header('Access-Control-Allow-Methods', 'POST,OPTIONS')

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        if not self.path.startswith('/proxy'):
            return self.send_error(404)
        q = urllib.parse.urlparse(self.path)
        target = (urllib.parse.parse_qs(q.query).get('u') or [''])[0]
        if not target.startswith(ALLOW_HOSTS):
            return self._json(403, {'error': {'message': '这个地址不在代理白名单里：' + target}})
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n)
        req = urllib.request.Request(target, data=body, method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('Accept', 'application/json')
        req.add_header('User-Agent', 'cuoti-local-proxy/1.0')
        auth = self.headers.get('Authorization')
        if auth:
            req.add_header('Authorization', auth)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                data = r.read()
                code = r.status
        except urllib.error.HTTPError as e:
            data = e.read() or json.dumps({'error': {'message': str(e)}}).encode()
            code = e.code
        except Exception as e:  # noqa: BLE001
            data = json.dumps({'error': {'message': '代理转发失败：%s' % e}}, ensure_ascii=False).encode()
            code = 502
        self._json(code, data)

    def _json(self, code, data):
        if isinstance(data, (dict, list)):
            data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self._cors()
        self.end_headers()
        self.wfile.write(data)


if __name__ == '__main__':
    os.chdir(ROOT)
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    print('错题集服务已启动： http://127.0.0.1:%d' % PORT)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
