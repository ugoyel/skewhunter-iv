#!/usr/bin/env python3
"""Bridges a real Linux TUN device to ClearTether's user-space stack for end-to-end tests.

The Linux kernel's own TCP implementation is the "app": test sockets connect to addresses
routed into the TUN device, and every packet is forwarded to the JVM test over a localhost TCP
socket (2-byte big-endian length prefix per packet). Optional random packet loss exercises
retransmission in both directions.

Usage (root): tun_bridge.py <listen-port> [drop-probability]
"""
import fcntl
import os
import random
import select
import socket
import struct
import subprocess
import sys

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000
IFNAME = "ctk0"


def main() -> None:
    port = int(sys.argv[1])
    drop = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    tun = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(tun, TUNSETIFF, struct.pack("16sH", IFNAME.encode(), IFF_TUN | IFF_NO_PI))
    subprocess.run(["ip", "addr", "add", "10.99.0.1/24", "dev", IFNAME], check=True)
    subprocess.run(["ip", "link", "set", IFNAME, "mtu", "1500", "up"], check=True)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    print("READY", flush=True)
    conn, _ = server.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    pending = b""
    try:
        while True:
            readable, _, _ = select.select([tun, conn], [], [])
            if tun in readable:
                packet = os.read(tun, 65535)
                if packet and packet[0] >> 4 == 4 and random.random() >= drop:
                    conn.sendall(struct.pack(">H", len(packet)) + packet)
            if conn in readable:
                data = conn.recv(262144)
                if not data:
                    break
                pending += data
                while len(pending) >= 2:
                    (length,) = struct.unpack(">H", pending[:2])
                    if len(pending) < 2 + length:
                        break
                    packet, pending = pending[2:2 + length], pending[2 + length:]
                    if random.random() >= drop:
                        os.write(tun, packet)
    finally:
        os.close(tun)


if __name__ == "__main__":
    main()
