#!/usr/bin/env python3
"""Hearby protocol probe: a dependency-free reference peer for debugging from a laptop.

  hearby_probe.py listen HOST [--port 47800] [--seconds 10]
      Join a Hearby host like a listener would and print what it sends: packet cadence,
      sequence gaps, timestamp spacing, Opus frame durations, clock offset and round trip.

  hearby_probe.py host [--port 47800] [--delay 350] [--seconds 0]
      Act as a host that streams Opus silence, so a listener app can be exercised
      (join, clock sync, jitter buffer, decoder, playout) without an Android host.

  hearby_probe.py discover [--seconds 3]
      Broadcast DISCOVER probes and list the hosts that answer.

A third, independent implementation of docs/PROTOCOL.md next to the Kotlin and Swift ones.
"""
import argparse
import os
import socket
import struct
import sys
import time

MAGIC = b"HB"
VERSION = 1
PORT = 47800
AUDIO, JOIN, JOIN_ACK, SYNC_REQ, SYNC_RESP, LEAVE, BYE, DISCOVER, DISCOVER_RESP, NOW_PLAYING, CHIRP, OFFSET, VIDEO = range(1, 14)
FRAME_US = 20_000
# The canonical Opus "silence" packet: TOC 0xF8 = CELT fullband, 20 ms, mono, one frame.
OPUS_SILENCE = bytes([0xF8, 0xFF, 0xFE])


def now_us():
    return time.monotonic_ns() // 1000


def header(kind):
    return MAGIC + bytes([VERSION, kind])


def kind_of(data):
    if len(data) < 4 or data[:2] != MAGIC or data[2] != VERSION:
        return None
    return data[3]


def name_field(name):
    raw = name.encode("utf-8")[:48]
    return bytes([len(raw)]) + raw


def read_name(data, offset):
    n = data[offset]
    return data[offset + 1:offset + 1 + n].decode("utf-8", "replace")


def opus_duration_us(packet):
    """Duration of an Opus packet from its TOC byte (RFC 6716 section 3.1)."""
    if not packet:
        return -1
    toc = packet[0]
    config = toc >> 3
    if config < 12:
        frame = (10_000, 20_000, 40_000, 60_000)[config & 3]
    elif config < 16:
        frame = 10_000 if config & 1 == 0 else 20_000
    else:
        frame = (2_500, 5_000, 10_000, 20_000)[config & 3]
    code = toc & 3
    if code == 0:
        frames = 1
    elif code in (1, 2):
        frames = 2
    else:
        if len(packet) < 2:
            return -1
        frames = packet[1] & 0x3F
    return frame * frames


def listen(args):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(0.05)
    host = (args.host, args.port)
    client_id = int.from_bytes(os.urandom(4), "big")
    joined = False
    stream_id = None
    last_join = last_sync = 0
    syncs = 0
    best = None  # (rtt, offset)
    packets = lost = 0
    last_seq = last_ts = None
    spacings, sizes, durations, arrivals = [], [], set(), []
    last_arrival = None
    now_playing = None
    video = None
    started = last_report = time.monotonic()

    while time.monotonic() - started < args.seconds:
        now = time.monotonic()
        if not joined and now - last_join > 0.4:
            last_join = now
            sock.sendto(header(JOIN) + struct.pack(">IB", client_id, 1) + name_field("hearby_probe"), host)
        if joined and now - last_sync > (0.04 if syncs < 8 else 1.0):
            last_sync = now
            syncs += 1
            rtt = best[0] if best else 0
            # last field: our playout adjustment (slider + sound-check correction), none here
            sock.sendto(header(SYNC_REQ) + struct.pack(">IQIHBBi", client_id, now_us(), rtt, 0, 2, 0, 0), host)
        try:
            data, _ = sock.recvfrom(2048)
        except socket.timeout:
            data = None
        except ConnectionRefusedError:
            data = None
        rx = now_us()
        kind = kind_of(data) if data else None
        if kind == JOIN_ACK:
            sid, rate, channels, frame_ms, delay = struct.unpack(">IIBBH", data[4:16])
            if not joined:
                print(f"joined '{read_name(data, 16)}': stream {sid:08x}, {rate} Hz, {channels} ch, "
                      f"{frame_ms} ms frames, playout delay {delay} ms")
            joined, stream_id = True, sid
        elif kind == SYNC_RESP:
            _, t0, t1, t2, _delay = struct.unpack(">IQQQH", data[4:34])
            rtt = (rx - t0) - (t2 - t1)
            offset = ((t1 - t0) + (t2 - rx)) // 2
            if rtt >= 0 and (best is None or rtt <= best[0]):
                best = (rtt, offset)
        elif kind == AUDIO:
            seq, ts = struct.unpack(">IQ", data[4:16])
            payload = data[16:]
            packets += 1
            sizes.append(len(payload))
            durations.add(opus_duration_us(payload))
            if last_seq is not None:
                gap = (seq - last_seq) & 0xFFFFFFFF
                if gap > 1:
                    lost += gap - 1
                if gap == 1:
                    spacings.append(ts - last_ts)
            if last_arrival is not None:
                arrivals.append(rx - last_arrival)
            last_seq, last_ts, last_arrival = seq, ts, rx
        elif kind == NOW_PLAYING:
            playing = data[8] & 1
            title = read_name(data, 9)
            artist = read_name(data, 10 + data[9])
            song = (f"{title} - {artist}" if artist else title) or "(nothing)"
            if (song, playing) != now_playing:
                now_playing = (song, playing)
                print(f"now playing: {song}{'' if playing else ' (paused)'}")
        elif kind == VIDEO and len(data) >= 31:
            version, flags, anchor, position = struct.unpack(">IBQq", data[8:29])
            vid = read_name(data, 29)
            title = read_name(data, 30 + data[29])
            state = (vid, flags, anchor, position)
            if state != video:
                video = state
                if not flags & 2:
                    print("youtube: off (back to music)")
                else:
                    # anchor is on the host's clock; best[1] is host minus our clock
                    starts = f", starts in {(anchor - (now_us() + best[1])) / 1e6:.2f} s" if best and flags & 1 else ""
                    print(f"youtube: {vid} '{title}' v{version} {'playing' if flags & 1 else 'paused'} "
                          f"from {position / 1e6:.2f} s{starts}")
        elif kind == CHIRP:
            target, media_us = struct.unpack(">IQ", data[8:20])
            if target == client_id:
                print(f"sound check: asked to chirp at media time {media_us} us (this probe has no speaker)")
        elif kind == BYE:
            print("host said BYE")
            break
        if now - last_report >= 1.0:
            last_report = now
            if packets:
                line = (f"{packets:5d} pkts  lost {lost}  payload {min(sizes)}-{max(sizes)} B "
                        f"(avg {sum(sizes) / len(sizes):.0f})  opus {sorted(durations)} us")
                if spacings:
                    line += (f"  ts step {min(spacings)}/{sum(spacings) / len(spacings):.0f}/{max(spacings)} us")
                if arrivals:
                    line += f"  arrival gap max {max(arrivals) / 1000:.1f} ms"
                if best:
                    line += f"  rtt {best[0]} us  offset {best[1]} us"
                    if last_ts is not None:
                        line += f"  capture-to-arrival {(last_arrival + best[1] - last_ts) / 1000:.1f} ms"
                print(line)
            else:
                print("joined, no audio yet" if joined else "waiting for the host to answer JOIN ...")
    if joined:
        sock.sendto(header(LEAVE) + struct.pack(">I", client_id), host)
    ok = packets > 0 and durations == {FRAME_US}
    print("RESULT:", "ok" if ok else "problem", f"packets={packets} lost={lost} durations={sorted(durations)}")
    return 0 if ok else 1


def host(args):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(0.002)
    stream_id = int.from_bytes(os.urandom(4), "big")
    clients = {}  # address -> dict
    seq = 0
    next_frame = now_us()
    started = last_report = time.monotonic()
    print(f"hosting Opus silence on UDP {args.port}, stream {stream_id:08x}, playout delay {args.delay} ms")

    while args.seconds <= 0 or time.monotonic() - started < args.seconds:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            data = None
        rx = now_us()
        kind = kind_of(data) if data else None
        if kind == JOIN:
            client_id, platform = struct.unpack(">IB", data[4:9])
            if addr not in clients:
                print(f"JOIN from {addr[0]}:{addr[1]} '{read_name(data, 9)}' platform {platform}")
            clients[addr] = {"seen": time.monotonic(), "status": 0, "rtt": 0, "buffer": 0}
            sock.sendto(header(JOIN_ACK) + struct.pack(">IIBBH", stream_id, 48_000, 2, 20, args.delay)
                        + name_field("hearby_probe"), addr)
        elif kind == SYNC_REQ and addr in clients:
            _cid, t0, rtt, buffer_ms, status, _ = struct.unpack(">IQIHBB", data[4:24])  # 24 or 28 bytes
            clients[addr].update(seen=time.monotonic(), status=status, rtt=rtt, buffer=buffer_ms)
            sock.sendto(header(SYNC_RESP) + struct.pack(">IQQQHH", stream_id, t0, rx, now_us(), args.delay, 0), addr)
        elif kind == LEAVE and addr in clients:
            print(f"LEAVE from {addr[0]}:{addr[1]}")
            del clients[addr]
        elif kind == DISCOVER:
            nonce = struct.unpack(">I", data[4:8])[0]
            sock.sendto(header(DISCOVER_RESP) + struct.pack(">IIB", nonce, stream_id, len(clients))
                        + name_field("hearby_probe"), addr)

        # One frame every 20 ms, stamped with the time its first sample "was captured".
        while now_us() >= next_frame + FRAME_US:
            packet = header(AUDIO) + struct.pack(">IQ", seq & 0xFFFFFFFF, next_frame) + OPUS_SILENCE
            for addr in list(clients):
                sock.sendto(packet, addr)
            seq += 1
            next_frame += FRAME_US

        now = time.monotonic()
        if now - last_report >= 2.0:
            last_report = now
            for addr in [a for a, c in clients.items() if now - c["seen"] > 6]:
                print(f"timeout {addr[0]}:{addr[1]}")
                del clients[addr]
            names = {0: "connecting", 1: "buffering", 2: "in sync", 3: "late"}
            for addr, c in clients.items():
                print(f"  {addr[0]}:{addr[1]}  {names.get(c['status'], c['status'])}  "
                      f"rtt {c['rtt'] / 1000:.1f} ms  buffer {c['buffer']} ms")
    for addr in clients:
        sock.sendto(header(BYE) + struct.pack(">I", stream_id), addr)
    return 0


def discover(args):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.3)
    found = {}
    end = time.monotonic() + args.seconds
    nonce = 1
    while time.monotonic() < end:
        sock.sendto(header(DISCOVER) + struct.pack(">I", nonce), ("255.255.255.255", args.port))
        nonce += 1
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            continue
        if kind_of(data) == DISCOVER_RESP:
            _nonce, sid, listeners = struct.unpack(">IIB", data[4:13])
            found[addr] = (read_name(data, 13), sid, listeners)
    for addr, (name, sid, listeners) in found.items():
        print(f"{addr[0]}:{addr[1]}  '{name}'  stream {sid:08x}  {listeners} listening")
    if not found:
        print("no hosts answered")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("listen")
    p.add_argument("host")
    p.add_argument("--port", type=int, default=PORT)
    p.add_argument("--seconds", type=float, default=10)
    p.set_defaults(run=listen)
    p = sub.add_parser("host")
    p.add_argument("--port", type=int, default=PORT)
    p.add_argument("--delay", type=int, default=350)
    p.add_argument("--seconds", type=float, default=0)
    p.set_defaults(run=host)
    p = sub.add_parser("discover")
    p.add_argument("--port", type=int, default=PORT)
    p.add_argument("--seconds", type=float, default=3)
    p.set_defaults(run=discover)
    args = parser.parse_args()
    sys.exit(args.run(args))


if __name__ == "__main__":
    main()
