#!/usr/bin/env python3
"""A fake OpenAI-compatible chat endpoint over HTTPS, for the end-to-end check of `--heal api`: no real model, no secret.

Usage: fake-llm-server.py CERT.pem KEY.pem EXPECTED_API_KEY
Prints "port=<n>" on the first line, then answers POST /v1/chat/completions with "pong" when the bearer token is right
and with HTTP 401 otherwise, until it is killed.
"""
import json
import ssl
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

cert, key, expected = sys.argv[1], sys.argv[2], sys.argv[3]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        if self.headers.get("Authorization") != "Bearer " + expected:
            self.reply(401, {"error": {"message": "Incorrect API key provided"}})
        elif self.path != "/v1/chat/completions" or not request.get("messages"):
            self.reply(404, {"error": {"message": "unexpected request " + self.path}})
        else:
            self.reply(200, {"choices": [{"message": {"role": "assistant", "content": "pong"}}]})

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


server = HTTPServer(("127.0.0.1", 0), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
server.socket = context.wrap_socket(server.socket, server_side=True)
print("port=%d" % server.server_address[1], flush=True)
server.serve_forever()
