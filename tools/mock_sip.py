#!/usr/bin/env python3
"""A stand-in for a VoIP.ms POP, for exercising registration and calls off a handset.

    python3 tools/mock_sip.py [--port 5060] [--media-port 40000] [--ring-seconds 2]

In a debug build, Settings reaches it through "Use the mock server", which also sets the
REST endpoint to tools/mock_voipms.py.

What it answers:

    REGISTER     401 with a Digest challenge, then 200 granting the expiry asked for
    INVITE      100, then 180 Ringing, then 200 OK after --ring-seconds
    ACK         nothing, as the RFC says
    BYE         200, and the media socket closes
    CANCEL      200, and the ring stops

What it deliberately does not do is SRTP. Encrypting media needs a key exchange at both
ends, so a mock that "supported" it would only prove that the mock agrees with itself. It
answers plain RTP/AVP with PCMU and echoes the RTP it receives, which is enough to hear
yourself and to watch the counters move.

The identity is not checked: anything presented is accepted, because the point is the
exchange, not the password. That also means this must never be reachable off this machine.
"""

import argparse
import random
import re
import socket
import threading
import time

REALM = "mock"
MAX_EXPIRY = 3600

_log_lock = threading.Lock()


def log(note):
    with _log_lock:
        print(f"{time.strftime('%H:%M:%S')}  {note}", flush=True)


def parse(data):
    """Split a SIP message into its start line, method, headers and body."""
    text = data.decode("utf-8", "replace")
    head, _, body = text.partition("\r\n\r\n")
    lines = head.split("\r\n")
    start = lines[0] if lines else ""
    headers = {}
    for line in lines[1:]:
        name, separator, value = line.partition(":")
        if separator:
            headers[name.strip().lower()] = value.strip()
    method = start.split(" ")[0] if start else ""
    return start, method, headers, body


def nonce():
    return "%032x" % random.getrandbits(128)


def answering(headers, code, reason, extra=(), body=""):
    """A response to the request these headers came from.

    Via, From, Call-ID and CSeq are echoed verbatim, which is what makes the client match
    the answer to its own transaction. To gains a tag when the request did not carry one.
    """
    to = headers.get("to", "")
    if "tag=" not in to:
        to = f"{to};tag=mock{random.randint(1000, 9999)}"
    lines = [
        f"SIP/2.0 {code} {reason}",
        f"Via: {headers.get('via', '')}",
        f"From: {headers.get('from', '')}",
        f"To: {to}",
        f"Call-ID: {headers.get('call-id', '')}",
        f"CSeq: {headers.get('cseq', '')}",
        *[f"{name}: {value}" for name, value in extra],
        "Server: mock-sip",
        f"Content-Length: {len(body.encode())}",
    ]
    return ("\r\n".join(lines) + "\r\n\r\n" + body).encode()


def sdp_answer(host, media_port):
    return "\r\n".join([
        "v=0",
        f"o=- {int(time.time())} 1 IN IP4 {host}",
        "s=-",
        f"c=IN IP4 {host}",
        "t=0 0",
        f"m=audio {media_port} RTP/AVP 0 101",
        "a=rtpmap:0 PCMU/8000",
        "a=rtpmap:101 telephone-event/8000",
        "a=fmtp:101 0-16",
        "a=ptime:20",
        "a=sendrecv",
        "",
    ])


class Media:
    """Echoes RTP back to whoever sent it.

    Replying to the source address rather than to the address in the SDP is deliberate:
    behind NAT those differ, and answering where the packet came from is what makes audio
    come back at all.
    """

    def __init__(self, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", port))
        self.sock.settimeout(0.5)
        self.port = port
        self.running = False
        self.ssrc = random.getrandbits(32)
        self.sequence = random.getrandbits(16)
        self.timestamp = random.getrandbits(32)
        self.received = 0

    def start(self):
        if self.running:
            return
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def stop(self):
        self.running = False

    def _run(self):
        while self.running:
            try:
                packet, source = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            if len(packet) < 12:
                continue
            payload = packet[12:]
            self.received += 1
            header = (
                b"\x80"
                + packet[1:2]
                + self.sequence.to_bytes(2, "big")
                + self.timestamp.to_bytes(4, "big")
                + self.ssrc.to_bytes(4, "big")
            )
            try:
                self.sock.sendto(header + payload, source)
            except OSError:
                return
            self.sequence = (self.sequence + 1) & 0xFFFF
            self.timestamp = (self.timestamp + 160) & 0xFFFFFFFF


def expiry_of(headers):
    """What the client asked for, from Contact's expires or the Expires header."""
    asked = re.search(r"expires=(\d+)", headers.get("contact", ""), re.I)
    if asked:
        return int(asked.group(1))
    return int(headers.get("expires", MAX_EXPIRY) or 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5060)
    parser.add_argument("--media-port", type=int, default=40000)
    parser.add_argument("--ring-seconds", type=float, default=2.0)
    parser.add_argument("--host", default="10.0.2.2",
                        help="address advertised in SDP, as the emulator sees this machine")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", args.port))
    media = Media(args.media_port)
    log(f"mock SIP on {args.host}:{args.port}, media on {args.media_port}")

    while True:
        data, source = sock.recvfrom(4096)
        start, method, headers, _ = parse(data)
        who = f"{source[0]}:{source[1]}"

        if method == "REGISTER":
            if "authorization" not in headers and "proxy-authorization" not in headers:
                log(f"REGISTER from {who} -> 401")
                sock.sendto(
                    answering(headers, 401, "Unauthorized", [
                        ("WWW-Authenticate",
                         f'Digest realm="{REALM}", nonce="{nonce()}", '
                         f'algorithm=MD5, qop="auth"'),
                    ]),
                    source,
                )
                continue
            asked = expiry_of(headers)
            if asked == 0:
                log(f"REGISTER from {who} -> 200, binding removed")
                sock.sendto(answering(headers, 200, "OK", [("Expires", "0")]), source)
                continue
            granted = min(asked, MAX_EXPIRY)
            contact = re.sub(r";expires=\d+", "", headers.get("contact", ""), flags=re.I)
            log(f"REGISTER from {who} -> 200, granted {granted} s")
            sock.sendto(
                answering(headers, 200, "OK", [
                    ("Expires", str(granted)),
                    ("Contact", f"{contact};expires={granted}"),
                ]),
                source,
            )
            continue

        if method == "INVITE":
            log(f"INVITE {headers.get('to', '')} from {who} -> 100, 180, 200")
            sock.sendto(answering(headers, 100, "Trying"), source)
            sock.sendto(answering(headers, 180, "Ringing"), source)
            # Ring for a moment so the caller's own ringback is exercised, then answer.
            time.sleep(args.ring_seconds)
            media.start()
            sock.sendto(
                answering(headers, 200, "OK",
                          [("Content-Type", "application/sdp")],
                          sdp_answer(args.host, args.media_port)),
                source,
            )
            continue

        if method == "ACK":
            continue

        if method == "BYE":
            log(f"BYE -> 200, {media.received} media packet(s) echoed")
            media.stop()
            sock.sendto(answering(headers, 200, "OK"), source)
            continue

        if method in ("CANCEL", "OPTIONS"):
            log(f"{method} -> 200")
            sock.sendto(answering(headers, 200, "OK"), source)
            continue

        log(f"{method or start[:20]} from {who} -> 501")
        sock.sendto(answering(headers, 501, "Not Implemented"), source)


if __name__ == "__main__":
    main()
