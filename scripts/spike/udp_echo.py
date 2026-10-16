#!/usr/bin/env python3
"""UDP echo server for the spike's network latency test (UdpLatencyProbe): run it on a computer on the same Wi-Fi.

    python3 scripts/spike/udp_echo.py [port]   # default 47800
"""
import socket
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 47800
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", port))
print(f"echoing UDP on port {port}", flush=True)
while True:
    data, addr = sock.recvfrom(2048)
    sock.sendto(data, addr)
