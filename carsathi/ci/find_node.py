"""Prints the centre "x y" of the first UI node whose text/content-desc contains the query."""
import re
import sys
import xml.etree.ElementTree as ET

path, query = sys.argv[1], sys.argv[2].lower()
exact = query.startswith("=")
query = query.lstrip("=")
index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
matches = []
for node in ET.parse(path).iter("node"):
    text = (node.get("text") or "").lower()
    desc = (node.get("content-desc") or "").lower()
    hit = (query in (text, desc)) if exact else (query in text or query in desc)
    if hit:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        if x2 > x1 and y2 > y1:
            matches.append(((x1 + x2) // 2, (y1 + y2) // 2))
if not matches or index >= len(matches) or -index > len(matches):
    sys.exit(1)
print(*matches[index])
