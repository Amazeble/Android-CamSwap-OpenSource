#!/usr/bin/env python3
"""Disable replaceJpegImageIfNeeded (JPEG replacement off)."""
import os, re, sys

# Locate Camera2SessionHook.java
target = None
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in {".git","build",".gradle","_filtered_out"}]
    for f in files:
        if f == "Camera2SessionHook.java":
            target = os.path.join(root, f); break
    if target: break

if not target:
    sys.exit("Camera2SessionHook.java not found")

src = open(target, encoding="utf-8").read()

def find_matching_brace(content, open_idx):
    depth, i, n = 0, open_idx, len(content)
    in_str = in_char = in_line = in_block = esc = False
    while i < n:
        c = content[i]; nxt = content[i+1] if i+1 < n else ""
        if in_line:
            if c == "\n": in_line = False
        elif in_block:
            if c == "*" and nxt == "/": in_block = False; i += 1
        elif in_str:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': in_str = False
        elif in_char:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == "'": in_char = False
        else:
            if c == "/" and nxt == "/": in_line = True; i += 1
            elif c == "/" and nxt == "*": in_block = True; i += 1
            elif c == '"': in_str = True
            elif c == "'": in_char = True
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0: return i
        i += 1
    return -1

# Find the method and replace its body with a no-op
pattern = r"\bboolean\s+replaceJpegImageIfNeeded\s*\("
m = re.search(pattern, src)
if not m:
    sys.exit("replaceJpegImageIfNeeded not found in " + target)

brace = src.find("{", m.end())
end = find_matching_brace(src, brace)
if end == -1:
    sys.exit("Could not find method body end")

new_body = """
        // ===== JPEG REPLACEMENT DISABLED =====
        // Captured JPEG now passes through untouched (real scene).
        return false;
    """

src = src[:brace+1] + new_body + src[end:]
open(target, "w", encoding="utf-8").write(src)

print("✅ replaceJpegImageIfNeeded disabled (returns false immediately)")
print("   File:", target)
print("   Effect: captured photo = real camera scene, NOT the video")
print("   Rebuild: ./gradlew clean assembleDebug")