"""End-to-end socket cancellation with a fake parser, no model/GPU required."""
import asyncio
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path

import httpx


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class GatewayHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_disconnect_kills_parser_and_following_request_succeeds(self):
        port, child_port = free_port(), free_port()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "started"
            fake = root / "parser.py"
            fake.write_text('''import os, sys, time
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if body == b'blocked':
            Path(sys.argv[2]).write_text(str(os.getpid()))
            time.sleep(60)
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"markdown":"next"}')
HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
''')
            launcher = root / "launch.py"
            launcher.write_text(f'''import uvicorn, cancellable_api
cancellable_api.worker = cancellable_api.Worker([{sys.executable!r}, {str(fake)!r}, {str(child_port)!r}, {str(marker)!r}], port={child_port})
uvicorn.run(cancellable_api.app, host="127.0.0.1", port={port}, log_level="warning")
''')
            env = dict(os.environ, PYTHONPATH=str(Path(__file__).parent.resolve()))
            server = await asyncio.create_subprocess_exec(sys.executable, str(launcher), env=env,
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            try:
                async with httpx.AsyncClient(trust_env=False) as client:
                    async with asyncio.timeout(10):
                        while True:
                            try:
                                if (await client.get(f"http://127.0.0.1:{port}/openapi.json")).status_code == 200:
                                    break
                            except httpx.TransportError:
                                pass
                            await asyncio.sleep(0.1)
                    old = asyncio.create_task(client.post(f"http://127.0.0.1:{port}/file_parse", content=b"blocked", timeout=20))
                    async with asyncio.timeout(10):
                        while not marker.exists():
                            await asyncio.sleep(0.05)
                    old_pid = int(marker.read_text())
                    old.cancel()
                    with self.assertRaises(asyncio.CancelledError):
                        await old
                    async with asyncio.timeout(5):
                        while True:
                            try: os.kill(old_pid, 0)
                            except ProcessLookupError: break
                            await asyncio.sleep(0.05)
                    response = await client.post(f"http://127.0.0.1:{port}/file_parse", content=b"next", timeout=10)
                    self.assertEqual(response.status_code, 200)
                    self.assertEqual(response.json(), {"markdown": "next"})
            finally:
                server.terminate()
                await asyncio.wait_for(server.wait(), 5)


if __name__ == "__main__":
    unittest.main()
