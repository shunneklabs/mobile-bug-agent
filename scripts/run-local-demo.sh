#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
GRADLEW="${ROOT_DIR}/gradlew"
PORT="${MBA_SERVER_PORT:-8080}"
HEALTH_URL="${MBA_HEALTH_URL:-http://localhost:${PORT}/health}"
PACKAGE_NAME="dev.sunnat629.mba.sample"

free_port() {
  if lsof -ti tcp:"${PORT}" >/dev/null 2>&1; then
    echo "⚠️  Port ${PORT} is busy — killing stale listener(s)..."
    lsof -ti tcp:"${PORT}" | xargs kill -9 >/dev/null 2>&1 || true
    sleep 1
  fi
}

if [[ ! -x "${GRADLEW}" ]]; then
  echo "❌ gradlew not found or not executable at: ${GRADLEW}"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "❌ curl is required but not installed."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ adb is required but not found in PATH."
  exit 1
fi

# Preflight — make sure no stale JVM is holding the port
free_port

echo "🚀 Starting Ktor server (:mba-server:run) on port ${PORT}..."
(
  cd "${ROOT_DIR}"
  "${GRADLEW}" :mba-server:run
) &
SERVER_PID=$!

cleanup() {
  echo
  echo "🛑 Cleaning up server on port ${PORT}..."
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "${SERVER_PID}" >/dev/null 2>&1; then
    kill "${SERVER_PID}" >/dev/null 2>&1 || true
  fi
  # Also kill the forked JVM that actually holds the port
  if lsof -ti tcp:"${PORT}" >/dev/null 2>&1; then
    lsof -ti tcp:"${PORT}" | xargs kill -9 >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

echo "⏳ Waiting for server health at ${HEALTH_URL} ..."
for _ in {1..90}; do
  if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
    echo "✅ Server is healthy."
    break
  fi
  sleep 1
done

if ! curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
  echo "❌ Server did not become healthy in time."
  exit 1
fi

echo "📦 Installing Android sample app (:mba-sample:installDebug)..."
(
  cd "${ROOT_DIR}"
  "${GRADLEW}" :mba-sample:installDebug
)

if adb devices | awk 'NR>1 && $2=="device" { found=1 } END { exit(found ? 0 : 1) }'; then
  echo "📱 Launching ${PACKAGE_NAME} ..."
  adb shell monkey -p "${PACKAGE_NAME}" -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "✅ App launched (or launch intent sent)."
else
  echo "⚠️  No online Android device/emulator detected. App install step ran, but launch was skipped."
fi

echo "🎉 Server + app flow complete. Open http://localhost:${PORT}/booth in your browser. Press Ctrl+C to stop the server."
wait "${SERVER_PID}"
