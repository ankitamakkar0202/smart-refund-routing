#!/usr/bin/env bash
# compile.sh — Build and run the Smart Refund Routing System
#
# Usage:
#   ./compile.sh            — compile main sources
#   ./compile.sh test       — compile + run tests
#   ./compile.sh run        — compile + start the server

set -euo pipefail

SRC_MAIN="src/main/java"
SRC_TEST="src/test/java"
RESOURCES="src/main/resources"
OUT_MAIN="target/classes"
OUT_TEST="target/test-classes"
MAIN_CLASS="com.refund.routing.server.RefundRoutingServer"
TEST_CLASS="com.refund.routing.RefundRoutingTest"

# ── Compile main sources ──────────────────────────────────────────────────────
echo "Compiling main sources..."
mkdir -p "$OUT_MAIN"
find "$SRC_MAIN" -name "*.java" | xargs javac -d "$OUT_MAIN"
# Copy resources onto classpath
cp -r "$RESOURCES"/. "$OUT_MAIN"/
echo "Build successful → $OUT_MAIN"

# ── Subcommands ───────────────────────────────────────────────────────────────
case "${1:-}" in
  test)
    echo ""
    echo "Compiling tests..."
    mkdir -p "$OUT_TEST"
    find "$SRC_TEST" -name "*.java" | xargs javac -cp "$OUT_MAIN" -d "$OUT_TEST"
    echo "Running tests..."
    java -cp "$OUT_MAIN:$OUT_TEST" "$TEST_CLASS"
    ;;
  run)
    echo ""
    echo "Starting server..."
    java -cp "$OUT_MAIN" "$MAIN_CLASS"
    ;;
  *)
    echo ""
    echo "Done. Run with:  ./compile.sh run   (server)"
    echo "             or: ./compile.sh test  (test suite)"
    ;;
esac