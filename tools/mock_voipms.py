#!/usr/bin/env python3
"""A stand-in for the VoIP.ms REST endpoint, for exercising the app's polling.

    python3 tools/mock_voipms.py [--port 8765] [--delay-ms 0]

Point the app at it from Settings: long-press the "Sync" heading to reveal the
endpoint field, then enter http://10.0.2.2:8765/rest.php for an emulator.

A phone cannot reach it from the app as shipped: a release build refuses cleartext to any
host, and the debug config permits 10.0.2.2 alone, which is the emulator's alias for this
machine. A phone test needs this machine's LAN address added to
android/app/src/debug/res/xml/network_security_config.xml.

It answers getDIDsInfo, getMMS, getSMS, sendSMS and the voicemail methods with the shapes
the real API uses, and it can be told to misbehave, which is hard to arrange against the
real thing:

    curl -s localhost:8765/control/state
    curl -s "localhost:8765/control/inbound?did=5551234567&contact=5551110001&text=hello"
    curl -s "localhost:8765/control/mms?count=2&kind=image"
    curl -s "localhost:8765/control/mms?kind=garbage"     # lies about its content type
    curl -s "localhost:8765/control/mms?kind=gone"        # the link has expired
    curl -s "localhost:8765/control/mms?kind=huge"        # 24 MB, to prove the cap
    curl -s "localhost:8765/control/voicemail?mailbox=1001&callerid=5551110009&duration=00:00:04"
    curl -s "localhost:8765/control/fail?code=522&count=2"
    curl -s "localhost:8765/control/delay?ms=1500"
    curl -s localhost:8765/control/reset

Attachments are served from `/media/<name>`, and the links handed out by getMMS are built
from the request's own host, so the emulator's 10.0.2.2 reaches this process too.

Every API call is logged with a timestamp, so the polling cadence is visible in
the terminal while you watch the app.
"""

import argparse
import base64
import io
import json
import math
import re
import struct
import threading
import time
import wave
import zlib
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()

DIDS = [
    {
        "did": "5551234567",
        "description": "Mock main",
        "routing": "account:100001_VoIP",
        "voicemail": "1001",
        "pop": "16",
        "billing_type": "2",
        "cnam": "1",
        "sms_available": "1",
        "sms_enabled": "1",
        "sms_email": "mock@example.com",
        "sms_email_enabled": "0",
        "sms_url_callback": "",
        "sms_url_callback_enabled": "0",
        "smpp_enabled": "0",
    },
    {
        "did": "5559876543",
        "description": "Mock side, no SMS on this one",
        "routing": "account:100001_VoIP",
        # Points at the same mailbox as the first DID on purpose: mailboxes are not tied
        # one-to-one to numbers, and pointing several at one box is a normal setup.
        "voicemail": "1001",
        "pop": "16",
        "billing_type": "2",
        "cnam": "0",
        "sms_available": "0",
        "sms_enabled": "0",
    },
]

# Voicemail mailboxes. Two relationships matter and both are modelled here: mailbox 1001
# is shared by every DID above, and mailbox 1002 has no DID pointing at it at all, which
# is what an unmapped or freshly created box looks like. `password` is in the real
# getVoicemails payload; the app must ignore it, so the mock serves it and the parser has
# to drop it.
MAILBOXES = [
    {"mailbox": "1001", "name": "Mock main", "transcription": "yes", "password": "1234"},
    {"mailbox": "1002", "name": "Unrouted box", "transcription": "no", "password": "4321"},
]

# Exactly the fields the documented getVoicemailMessages output carries. Anything else
# kept here (a transcript, a clock time) is internal and must not leak, or the app would
# start depending on something the real API never sends.
VM_FIELDS = ("mailbox", "folder", "message_num", "date", "callerid", "duration",
             "urgent", "listened")

# The generated tone served for mp3 requests. A file rather than a base64 literal so the
# source stays readable; see audio_b64 for how it was made.
VOICEMAIL_MP3 = Path(__file__).with_name("voicemail_tone.mp3")

STATE = {
    "messages": [],
    "next_id": 0,
    "voicemails": [],
    "next_vm": 0,
    "fail_code": 0,
    "fail_remaining": 0,
    "delay_ms": 0,
    # How many audio requests still answer `data: "false"` instead of bytes. That is what
    # a real account sends for a recording it cannot serve at that moment, and it succeeds
    # on the next request, so the app retries. Nothing else in the mock can reproduce it.
    "audio_false_remaining": 0,
    # When on, the list call drops the text of any row containing non-ASCII, which is what a
    # real account did for a message that was a single emoji while the by-id call still had
    # it. Off by default because it is a simulation of a vendor quirk, not normal behaviour.
    "strip_list_bodies": False,
}

# The vendor doc's own example shows "hello+john", which implies the API urlencodes
# message text. Whether the live API still does is unconfirmed, so this is a switch
# rather than an assumption: --encode-spaces mimics the doc, the default sends plain
# text the way a modern response reads.
ENCODE_SPACES = False


def stamp():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def add_message(did, contact, text, incoming, carrier_status="Information not available",
                media=()):
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
            # Names, not URLs. The absolute link is built when the row is served, because
            # only then is the request's own Host known, and 10.0.2.2 from the emulator has
            # to reach the same mock as localhost from the host.
            "media": list(media),
        }
        STATE["messages"].append(message)
        return message


def tiny_png(width=48, height=48, rgb=(70, 130, 190)):
    """A solid PNG, built here so the mock needs no image library.

    Real bytes with a real signature, because the point of the fixture is that the app has
    to identify the type from the content rather than from the URL or a header.
    """
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind, data):
        body = kind + data
        return (struct.pack(">I", len(data)) + body +
                struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def public_vm(entry):
    """The documented shape only, so the app cannot depend on mock-only fields."""
    return {field: entry[field] for field in VM_FIELDS}


def mailbox_row(mailbox):
    """A getVoicemails row, with counts computed from what is actually stored."""
    with LOCK:
        rows = [v for v in STATE["voicemails"] if v["mailbox"] == mailbox["mailbox"]]
    row = dict(mailbox)
    row["new"] = sum(1 for v in rows if v["listened"] == "no")
    row["urgent"] = sum(1 for v in rows if v["urgent"] == "yes")
    return row


def add_voicemail(mailbox, callerid, duration="00:00:05", urgent=False, listened=False,
                  transcript=None):
    with LOCK:
        STATE["next_vm"] += 1
        now = datetime.now()
        entry = {
            "mailbox": mailbox,
            "folder": "Old" if listened else ("Urgent" if urgent else "INBOX"),
            "message_num": STATE["next_vm"],
            "date": now.strftime("%Y-%m-%d"),
            "callerid": callerid,
            "duration": duration,
            "urgent": "yes" if urgent else "no",
            "listened": "yes" if listened else "no",
            # Internal. The message list carries a date only, but a transcription
            # carries a clock time, so one is kept to be able to serve that.
            "time": now.strftime("%H:%M:%S"),
            "transcript": transcript,
        }
        STATE["voicemails"].append(entry)
        return entry


def audio_b64(entry, fmt="mp3", rate=8000, cap_seconds=4):
    """The recording bytes, base64 encoded.

    mp3 unless wav is asked for, because that is what the real API does: its `format`
    parameter defaults to mp3, with wav, wav49 and gsm as the alternatives. The mp3 is a
    tone generated once with

        ffmpeg -f lavfi -i 'sine=frequency=440:duration=12' -ac 1 -ar 22050 \\
               -b:a 24k -y tools/voicemail_tone.mp3

    and kept as a file rather than a base64 literal so this source stays readable. Twelve
    seconds because the shortest seeded message is six: a fixture shorter than the
    duration the same row advertises made seek look broken when it was working, since
    there was nothing left to seek into. The seeded durations stay as they are because
    they are metadata the app displays, not a description of this file.

    The wav branch synthesises its tone, which needs no encoder.
    """
    if fmt == "mp3" and VOICEMAIL_MP3.exists():
        return base64.b64encode(VOICEMAIL_MP3.read_bytes()).decode()
    seconds = 0
    for part in entry["duration"].split(":"):
        seconds = seconds * 60 + int(part)
    seconds = max(1, min(seconds, cap_seconds))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        frames = bytearray()
        for i in range(rate * seconds):
            frames += struct.pack("<h", int(3000 * math.sin(2 * math.pi * 440 * i / rate)))
        wav.writeframes(bytes(frames))
    return base64.b64encode(buf.getvalue()).decode()


def id_base():
    """Where the next message id starts.

    Not a small constant, because the app dedupes on (did, remote_id) and a long-lived
    emulator database would silently ignore a "new" message that reused an id it already
    held. Seeding from the clock keeps restarts from colliding without anyone having to
    remember to clear the app's data before an MMS test.
    """
    return int(time.time() * 1000)


def seed():
    STATE["messages"] = []
    STATE["next_id"] = id_base()
    add_message("5551234567", "5551110001", "first of the day", incoming=True)
    add_message("5551234567", "5551110001", "second one", incoming=True)
    add_message("5551234567", "5551110001", "replied earlier", incoming=False,
                carrier_status="delivered")
    STATE["voicemails"] = []
    STATE["next_vm"] = 0
    add_voicemail("1001", "5551110002", duration="00:00:06", transcript="call me back about the invoice")
    add_voicemail("1001", "5551110003", duration="00:00:03", urgent=True)
    add_voicemail("1001", "5551110004", duration="00:00:12", listened=True)
    add_voicemail("1002", "5551110005", duration="00:00:04", transcript="wrong number, sorry")


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

    def send_bytes(self, code, body, content_type="application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
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
        # Blank values are kept, so `text=` means an empty body rather than a missing
        # parameter. A message that arrives with no text is a case worth being able to test.
        fields = parse_qs(parsed.query, keep_blank_values=True)
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
        if parsed.path.startswith("/media/"):
            return self.media(parsed)
        return self.api()

    def media(self, parsed):
        """An attachment link, the way `media.php?map=...` behaves.

        Four variants, because each one is a failure the app has to survive: a real image,
        a file that lies about being one, an expired link, and something too large to hold
        in memory. The name decides, so a test can ask for the one it wants.
        """
        name = parsed.path[len("/media/"):]
        self.log_api(f"media {name}")
        if name.startswith("gone"):
            return self.send_bytes(404, b"expired", "text/plain")
        if name.startswith("garbage"):
            # Declared as a JPEG, actually text. The header is the sender's word.
            return self.send_bytes(200, b"this is not an image at all." * 20, "image/jpeg")
        if name.startswith("huge"):
            # Enough to prove the cap stops it, without allocating anything clever.
            return self.send_bytes(200, b"\x00" * (24 * 1024 * 1024), "image/png")
        return self.send_bytes(200, tiny_png(), "image/png")

    def control(self, parsed):
        fields = self.params()
        route = parsed.path.rstrip("/")
        if route == "/control/state":
            # mailbox_row takes the lock, so it is called before the lock below rather
            # than inside it: threading.Lock is not reentrant.
            mailboxes = [mailbox_row(m) for m in MAILBOXES]
            with LOCK:
                return self.send_json({
                    "dids": DIDS,
                    "messages": STATE["messages"],
                    "mailboxes": mailboxes,
                    "voicemails": STATE["voicemails"],
                    "fail_code": STATE["fail_code"],
                    "fail_remaining": STATE["fail_remaining"],
                    "delay_ms": STATE["delay_ms"],
                })
        if route == "/control/voicemail":
            entry = add_voicemail(
                fields.get("mailbox", MAILBOXES[0]["mailbox"]),
                fields.get("callerid", "5551110009"),
                duration=fields.get("duration", "00:00:05"),
                transcript=fields.get("transcript") or None,
            )
            self.log_api(
                f"control: added voicemail {entry['mailbox']}/{entry['message_num']}"
            )
            return self.send_json({"added": public_vm(entry)})
        if route == "/control/mms":
            count = max(1, min(3, int(fields.get("count", 1))))
            kind = fields.get("kind", "image")
            message = add_message(
                fields.get("did", DIDS[0]["did"]),
                fields.get("contact", "5551110002"),
                fields.get("text", "picture from the mock"),
                incoming=True,
                media=[f"{kind}{index}.png" for index in range(1, count + 1)],
            )
            self.log_api(
                f"control: added mms id={message['id']} media={count} kind={kind}"
            )
            return self.send_json({"added": {k: v for k, v in message.items() if k != "media"},
                                   "media": message["media"]})
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
        if route == "/control/audio-not-ready":
            with LOCK:
                STATE["audio_false_remaining"] = int(fields.get("count", 1))
            self.log_api(
                "control: next %d audio call(s) answer data=false"
                % STATE["audio_false_remaining"]
            )
            return self.send_json({"count": STATE["audio_false_remaining"]})
        if route == "/control/strip-list-bodies":
            with LOCK:
                STATE["strip_list_bodies"] = fields.get("on", "1") not in ("0", "no", "false")
            self.log_api(f"control: strip_list_bodies={STATE['strip_list_bodies']}")
            return self.send_json({"strip_list_bodies": STATE["strip_list_bodies"]})
        if route == "/control/reset":
            with LOCK:
                STATE["fail_code"] = 0
                STATE["fail_remaining"] = 0
                STATE["delay_ms"] = 0
                STATE["audio_false_remaining"] = 0
                STATE["strip_list_bodies"] = False
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
            # The links are absolute and point back at whichever host asked, which is how
            # the emulator's 10.0.2.2 resolves to this process rather than to nothing.
            host = self.headers.get("Host", "localhost")
            out = []
            for m in rows:
                row = {k: v for k, v in m.items() if k != "media"}
                if STATE["strip_list_bodies"] and any(ord(ch) > 127 for ch in row.get("message", "")):
                    # The vendor quirk: the row is listed, its text is not.
                    self.log_api(f"getMMS id={row['id']} body dropped (non-ASCII)")
                    row["message"] = ""
                for index, name in enumerate(m.get("media", [])[:3], start=1):
                    row[f"col_media{index}"] = f"http://{host}/media/{name}"
                out.append(row)
            return self.send_json({"status": "success", "sms": out})

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

        # Every voicemail call below is keyed by mailbox, not by DID. The DID-to-mailbox
        # link is the `voicemail` field that getDIDsInfo already returns.
        if method in ("getVoicemailMessages", "getVoicemailMessageFile",
                      "markListenedVoicemailMessage", "getVoicemailTranscriptions"):
            if not fields.get("mailbox"):
                self.log_api(f"{method}: missing mailbox")
                return self.send_json({"status": "missing_mailbox"})

        if method == "getVoicemails":
            wanted = fields.get("mailbox", "")
            rows = [mailbox_row(m) for m in MAILBOXES
                    if not wanted or m["mailbox"] == wanted]
            self.log_api(f"getVoicemails -> {len(rows)} mailbox(es)")
            if not rows:
                return self.send_json({"status": "no_mailbox"})
            return self.send_json({"status": "success", "voicemails": rows})

        if method == "getVoicemailFolders":
            mailbox = fields.get("mailbox", "")
            with LOCK:
                extra = {v["folder"] for v in STATE["voicemails"]
                         if v["mailbox"] == mailbox}
            # The default folders exist on an account whether or not anything is filed in
            # them, so they are always listed and anything else follows.
            names = ["INBOX", "Urgent", "Old"]
            names += sorted(extra - set(names))
            self.log_api(f"getVoicemailFolders mailbox={mailbox} -> {','.join(names)}")
            return self.send_json({
                "status": "success",
                "folders": [{"value": name, "description": name} for name in names],
            })

        if method == "getVoicemailMessages":
            mailbox = fields.get("mailbox", "")
            folder = fields.get("folder", "")
            date_from = fields.get("date_from", "0000-00-00")
            date_to = fields.get("date_to", "9999-99-99")
            with LOCK:
                rows = [v for v in STATE["voicemails"]
                        if v["mailbox"] == mailbox
                        and (not folder or v["folder"] == folder)
                        and date_from <= v["date"] <= date_to]
            rows.sort(key=lambda v: (v["date"], v["message_num"]))
            self.log_api(f"getVoicemailMessages mailbox={mailbox} folder={folder or '*'} "
                         f"{date_from}..{date_to} -> {len(rows)} row(s)")
            return self.send_json({"status": "success",
                                   "messages": [public_vm(v) for v in rows]})

        if method == "getVoicemailMessageFile":
            mailbox = fields.get("mailbox", "")
            folder = fields.get("folder", "")
            number = str(fields.get("message_num", ""))
            with LOCK:
                rows = [v for v in STATE["voicemails"]
                        if v["mailbox"] == mailbox and v["folder"] == folder
                        and str(v["message_num"]) == number]
            self.log_api(f"getVoicemailMessageFile {mailbox}/{folder}/{number} "
                         f"format={fields.get('format', 'mp3')} -> {len(rows)} row(s)")
            if not rows:
                return self.send_json({"status": "message_not_found"})
            requested = fields.get("format", "mp3")
            with LOCK:
                not_ready = STATE["audio_false_remaining"] > 0
                if not_ready:
                    STATE["audio_false_remaining"] -= 1
            if not_ready:
                # What the real account sends instead of audio, so the app's retry has
                # something to be tested against.
                self.log_api(
                    f"getVoicemailMessageFile {mailbox}/{folder}/{number} -> data=false"
                )
                return self.send_json({"status": "success", "message": {
                    "mailbox": mailbox,
                    "folder": folder,
                    "message_num": rows[0]["message_num"],
                    "data": "false",
                }})
            # An object, not an array of one. That is what the real API answers, and
            # the vendor's documentation shows the array, so a mock that follows the
            # doc hides the mismatch until a phone hits the real server. Both are
            # accepted by the app; this serves the shape the account actually returns.
            return self.send_json({"status": "success", "message": {
                "mailbox": mailbox,
                "folder": folder,
                "message_num": rows[0]["message_num"],
                "data": audio_b64(rows[0], requested),
            }})

        if method == "markListenedVoicemailMessage":
            mailbox = fields.get("mailbox", "")
            folder = fields.get("folder", "")
            number = str(fields.get("message_num", ""))
            listened = fields.get("listened", "yes")
            with LOCK:
                rows = [v for v in STATE["voicemails"]
                        if v["mailbox"] == mailbox and v["folder"] == folder
                        and str(v["message_num"]) == number]
                if rows:
                    rows[0]["listened"] = "yes" if listened == "yes" else "no"
                    # The real API moves the message between INBOX and Old as a side
                    # effect, which is exactly why (folder, message_num) cannot be the
                    # app's identity for a message.
                    rows[0]["folder"] = "Old" if listened == "yes" else "INBOX"
            self.log_api(f"markListenedVoicemailMessage {mailbox}/{number} "
                         f"listened={listened} -> "
                         f"{rows[0]['folder'] if rows else 'not found'}")
            if not rows:
                return self.send_json({"status": "message_not_found"})
            return self.send_json({"status": "success"})

        if method == "getVoicemailTranscriptions":
            mailbox = fields.get("mailbox", "")
            folder = fields.get("folder", "")
            date_from = fields.get("date_from", "0000-00-00")
            date_to = fields.get("date_to", "9999-99-99")
            with LOCK:
                rows = [v for v in STATE["voicemails"]
                        if v["mailbox"] == mailbox and v["folder"] == folder
                        and v.get("transcript")
                        and date_from <= v["date"] <= date_to]
            self.log_api(f"getVoicemailTranscriptions mailbox={mailbox} "
                         f"folder={folder} -> {len(rows)} row(s)")
            # No message_num in this payload: the real response identifies a
            # transcription by date, caller and duration only.
            return self.send_json({"status": "success", "messages": [{
                "date": f"{v['date']} {v['time']}",
                "callerid": v["callerid"],
                "duration": v["duration"],
                "folder": v["folder"],
                "result": v["transcript"],
            } for v in rows]})

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
