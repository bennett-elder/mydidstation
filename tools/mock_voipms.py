#!/usr/bin/env python3
"""A stand-in for the VoIP.ms REST endpoint, for exercising the app's polling.

    python3 tools/mock_voipms.py [--port 8765] [--delay-ms 0]

Point the app at it from Settings: long-press the "Sync" heading to reveal the
endpoint field, then enter http://10.0.2.2:8765/rest.php for an emulator.

A phone on USB reaches it at http://localhost:8765/rest.php after

    adb reverse tcp:8765 tcp:8765

which tunnels the phone's loopback to this machine. That tunnel is the only reason
localhost appears in the app's network security config. A phone on Wi-Fi would need
this machine's LAN address added to that config, since localhost there is the phone
itself.

It answers getDIDsInfo, getMMS, getSMS and sendSMS with the shapes the real API
uses, and it can be told to misbehave, which is hard to arrange against the real
thing:

    curl -s localhost:8765/control/state
    curl -s "localhost:8765/control/inbound?did=5551234567&contact=5551110001&text=hello"
    curl -s "localhost:8765/control/fail?code=522&count=2"
    curl -s "localhost:8765/control/delay?ms=1500"
    curl -s localhost:8765/control/reset

Every API call is logged with a timestamp, so the polling cadence is visible in
the terminal while you watch the app.
"""

import argparse
import json
import re
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()

DIDS = [
    {"did": "5551234567", "description": "Mock main"},
    {"did": "5559876543", "description": "Mock side"},
]

STATE = {"messages": [], "next_id": 9000, "fail_code": 0, "fail_remaining": 0, "delay_ms": 0}

# The vendor doc's own example shows "hello+john", which implies the API urlencodes
# message text. Whether the live API still does is unconfirmed, so this is a switch
# rather than an assumption: --encode-spaces mimics the doc, the default sends plain
# text the way a modern response reads.
ENCODE_SPACES = False


def stamp():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def add_message(did, contact, text, incoming, carrier_status="Information not available"):
    with LOCK:
        STATE["next_id"] += 1
        body = text.replace(" ", "+") if ENCODE_SPACES else text
        message = {
            "id": STATE["next_id"],
            "date": stamp(),
            "type": "1" if incoming else "0",
            "did": did,
            "contact": contact,
            "message": body,
            "carrier_status": carrier_status,
        }
        STATE["messages"].append(message)
        return message


def seed():
    STATE["messages"] = []
    STATE["next_id"] = 9000
    add_message("5551234567", "5551110001", "first of the day", incoming=True)
    add_message("5551234567", "5551110001", "second one", incoming=True)
    add_message("5551234567", "5551110001", "replied earlier", incoming=False,
                carrier_status="delivered")


def parse_multipart(body, boundary):
    fields = {}
    for chunk in body.split(b"--" + boundary):
        chunk = chunk.strip(b"\r\n")
        if not chunk or chunk == b"--" or b"\r\n\r\n" not in chunk:
            continue
        head, _, value = chunk.partition(b"\r\n\r\n")
        match = re.search(rb'name="([^"]+)"', head)
        if match:
            fields[match.group(1).decode()] = value.decode("utf-8", "replace")
    return fields


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def log_api(self, note):
        clock = datetime.now().strftime("%H:%M:%S")
        print(f"{clock}  {self.command:4s} {self.path[:38]:38s} {note}", flush=True)

    def send_json(self, payload, code=200):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_raw(self, code, body, content_type="text/html"):
        raw = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def params(self):
        parsed = urlparse(self.path)
        fields = parse_qs(parsed.query)
        if self.command == "POST":
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            ctype = self.headers.get("Content-Type", "")
            if "multipart/form-data" in ctype and "boundary=" in ctype:
                boundary = ctype.split("boundary=", 1)[1].strip('"').encode()
                fields = parse_multipart(body, boundary)
            elif body:
                fields = parse_qs(body.decode())
        return {k: (v[0] if isinstance(v, list) else v) for k, v in fields.items()}

    def do_GET(self):
        self.handle_any()

    def do_POST(self):
        self.handle_any()

    def handle_any(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/control"):
            return self.control(parsed)
        return self.api()

    def control(self, parsed):
        fields = self.params()
        route = parsed.path.rstrip("/")
        if route == "/control/state":
            with LOCK:
                return self.send_json({
                    "dids": DIDS,
                    "messages": STATE["messages"],
                    "fail_code": STATE["fail_code"],
                    "fail_remaining": STATE["fail_remaining"],
                    "delay_ms": STATE["delay_ms"],
                })
        if route == "/control/inbound":
            message = add_message(
                fields.get("did", DIDS[0]["did"]),
                fields.get("contact", "5551110001"),
                fields.get("text", "inbound from the mock"),
                incoming=True,
            )
            self.log_api(f"control: added inbound id={message['id']}")
            return self.send_json({"added": message})
        if route == "/control/fail":
            with LOCK:
                STATE["fail_code"] = int(fields.get("code", 522))
                STATE["fail_remaining"] = int(fields.get("count", 1))
            self.log_api(f"control: next {STATE['fail_remaining']} call(s) fail {STATE['fail_code']}")
            return self.send_json({"fail_code": STATE["fail_code"], "count": STATE["fail_remaining"]})
        if route == "/control/delay":
            with LOCK:
                STATE["delay_ms"] = int(fields.get("ms", 0))
            self.log_api(f"control: delay {STATE['delay_ms']} ms")
            return self.send_json({"delay_ms": STATE["delay_ms"]})
        if route == "/control/reset":
            with LOCK:
                STATE["fail_code"] = 0
                STATE["fail_remaining"] = 0
                STATE["delay_ms"] = 0
            seed()
            self.log_api("control: reset")
            return self.send_json({"reset": True})
        return self.send_json({"error": "unknown control route"}, 404)

    def api(self):
        fields = self.params()
        method = fields.get("method", "")
        if not fields.get("api_username") or not fields.get("api_password"):
            self.log_api(f"{method}: missing credentials")
            return self.send_json({"status": "invalid_credentials"})

        with LOCK:
            delay = STATE["delay_ms"]
            failing = STATE["fail_remaining"] > 0
            if failing:
                STATE["fail_remaining"] -= 1
                code = STATE["fail_code"]
        if delay:
            time.sleep(delay / 1000.0)
        if failing:
            self.log_api(f"{method}: forced failure {code}")
            if code == 522:
                return self.send_raw(522, "<html><body>522 origin timeout</body></html>")
            return self.send_raw(
                500,
                '<?xml version="1.0"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">'
                "<env:Body><env:Fault><env:Reason><env:Text>Bad Request</env:Text></env:Reason>"
                "</env:Fault></env:Body></env:Envelope>",
                "application/soap+xml",
            )

        if method == "getDIDsInfo":
            self.log_api("dids")
            return self.send_json({"status": "success", "dids": DIDS})

        if method == "getMMS":
            did = fields.get("did", "")
            date_from = fields.get("from", "0000-00-00")
            date_to = fields.get("to", "9999-99-99")
            limit = int(fields.get("limit", 100))
            with LOCK:
                rows = [m for m in STATE["messages"]
                        if m["did"] == did and date_from <= m["date"][:10] <= date_to]
            rows.sort(key=lambda m: m["date"])
            rows = rows[-limit:]
            self.log_api(f"getMMS did={did} {date_from}..{date_to} -> {len(rows)} row(s)")
            if not rows:
                return self.send_json({"status": "no_sms"})
            return self.send_json({"status": "success", "sms": rows})

        if method == "getSMS":
            wanted = fields.get("sms", "")
            with LOCK:
                rows = [m for m in STATE["messages"] if str(m["id"]) == str(wanted)]
            self.log_api(f"getSMS id={wanted} -> {len(rows)} row(s)")
            if not rows:
                return self.send_json({"status": "no_sms"})
            return self.send_json({"status": "success", "sms": rows})

        if method == "sendSMS":
            message = add_message(fields.get("did", ""), fields.get("dst", ""),
                                  fields.get("message", ""), incoming=False)
            self.log_api(f"sendSMS did={message['did']} -> id={message['id']}")
            return self.send_json({"status": "success", "sms": message["id"]})

        self.log_api(f"{method}: unknown method")
        return self.send_json({"status": "invalid_method"})


def main():
    global ENCODE_SPACES
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--delay-ms", type=int, default=0)
    parser.add_argument("--encode-spaces", action="store_true",
                        help="send message bodies with + for spaces, as the vendor doc example shows")
    args = parser.parse_args()
    STATE["delay_ms"] = args.delay_ms
    ENCODE_SPACES = args.encode_spaces
    seed()
    print(f"mock VoIP.ms on http://0.0.0.0:{args.port}/rest.php", flush=True)
    print(f"  {len(STATE['messages'])} seeded message(s), control at /control/state", flush=True)
    print(f"  spaces encoded as +: {ENCODE_SPACES}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
