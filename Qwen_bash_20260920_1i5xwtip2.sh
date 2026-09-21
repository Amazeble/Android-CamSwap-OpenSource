#!/usr/bin/env bash
set -e

echo "=== CamSwap CaptureGate verification ==="

echo ""
echo "[1] CaptureGate.java exists?"
find app/src/main/java -name "CaptureGate.java" -print

echo ""
echo "[2] hookCaptureGate installed in Camera2Handler?"
grep -n "hookCaptureGate" app/src/main/java/io/github/zensu357/camswap/Camera2Handler.java || true

echo ""
echo "[3] markReady() inserted?"
grep -RIn "CaptureGate.markReady" app/src/main/java || true

echo ""
echo "[4] markNotReady() inserted?"
grep -RIn "CaptureGate.markNotReady" app/src/main/java || true

echo ""
echo "Done."