#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
GRADLEW="${ROOT_DIR}/gradlew"
PORT="${MBA_SERVER_PORT:-8080}"
HEALTH_URL="${MBA_HEALTH_URL:-http://localhost:${PORT}/health}"
PACKAGE_NAME="dev.sunnat629.mba.sample"
COMMAND="${1:-local}"
LOG_DIR="${ROOT_DIR}/build/mba-local-demo"
SERVER_LOG="${LOG_DIR}/server.log"
INSTALLED_SERVER="${ROOT_DIR}/mba-server/build/install/mba-server/bin/mba-server"

detect_lan_ip() {
  local iface ip

  for iface in en0 en1; do
    ip="$(ipconfig getifaddr "${iface}" 2>/dev/null || true)"
    if [[ -n "${ip}" ]]; then
      printf '%s' "${ip}"
      return 0
    fi
  done

  iface="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}' || true)"
  if [[ -n "${iface}" ]]; then
    ip="$(ipconfig getifaddr "${iface}" 2>/dev/null || true)"
    if [[ -n "${ip}" ]]; then
      printf '%s' "${ip}"
      return 0
    fi
  fi

  printf '%s' "<your-mac-lan-ip>"
}

has_physical_android_device() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ { found=1 } END { exit(found ? 0 : 1) }'
}

sample_backend_endpoint() {
  if [[ -n "${MBA_SAMPLE_BACKEND_ENDPOINT:-}" ]]; then
    printf '%s' "${MBA_SAMPLE_BACKEND_ENDPOINT}"
    return 0
  fi

  if command -v adb >/dev/null 2>&1 && has_physical_android_device; then
    printf 'http://%s:%s' "$(detect_lan_ip)" "${PORT}"
    return 0
  fi

  printf 'http://10.0.2.2:%s' "${PORT}"
}

usage() {
  cat <<EOF
Usage: scripts/run-local-demo.sh [command]

Commands:
  local         Start server, install sample app, open local booth flow (default)
  server        Start only the local server and keep terminal logs attached
  package       Build deployable mba-server distribution under mba-server/build/distributions
  deploy-local  Build installDist and run the packaged server with env from local.properties
  commands      Print useful deploy/log/debug commands
  help          Show this help

Environment:
  MBA_SERVER_PORT   Server port for local runs (default: 8080)
  PORT              Server port used by packaged/deployed server (default: MBA_SERVER_PORT)
  MBA_SAMPLE_BACKEND_ENDPOINT
                   Backend URL baked into mba-sample; use http://<mac-lan-ip>:8080 for phones

Deploy notes:
  - Local demo uses Gradle :mba-server:run, which injects secrets from local.properties.
  - Packaged/deployed server needs real environment variables: GEMINI_API_KEY,
    NOTION_API_KEY, NOTION_DATABASE_ID, MBA_SERVER_API_KEY, GITHUB_TOKEN,
    GITHUB_OWNER, GITHUB_REPO, and GITHUB_BASE_BRANCH when those paths are used.
EOF
}

print_operator_commands() {
  local lan_ip="$(detect_lan_ip)"
  local sample_endpoint="$(sample_backend_endpoint)"

  cat <<EOF

🪨 Useful commands

Run local demo:
  ./scripts/run-local-demo.sh

Run server only:
  ./scripts/run-local-demo.sh server

Package server for deploy:
  ./scripts/run-local-demo.sh package

Run packaged server locally like deployment:
  ./scripts/run-local-demo.sh deploy-local

Deploy artifact to a VPS (example):
  ./scripts/run-local-demo.sh package
  scp mba-server/build/distributions/mba-server.tar <user>@<host>:/opt/mba/
  ssh <user>@<host> 'cd /opt/mba && tar -xf mba-server.tar && PORT=8080 GEMINI_API_KEY=... ./mba-server/bin/mba-server'

Watch server logs in another terminal:
  tail -f ${SERVER_LOG}

Check backend health/report/booth from terminal:
  curl -i http://localhost:${PORT}/health
  curl -N http://localhost:${PORT}/events
  open http://localhost:${PORT}/booth?debug=1

Physical device on same Wi-Fi:
  Mac LAN backend URL: http://${lan_ip}:${PORT}
  Build/install sample with that URL:
    MBA_SAMPLE_BACKEND_ENDPOINT=http://${lan_ip}:${PORT} ./gradlew :mba-sample:installDebug
  Open booth from Mac browser:
    open http://localhost:${PORT}/booth?debug=1
  If phone cannot upload, check macOS Firewall allows incoming Java/Ktor traffic on port ${PORT}.

Current sample backend endpoint for install:
  ${sample_endpoint}

Watch Android SDK / worker logs:
  adb logcat -c
  adb logcat -v time -s MBAAndroid CrashUploadWorker ServerReportUploader PendingCrashProcessor MBACrashHandler MBA WorkManager

Check emulator can reach host backend:
  adb shell 'toybox nc -vz 10.0.2.2 ${PORT} || true'

Check physical device can reach same-Wi-Fi backend:
  adb shell 'toybox nc -vz ${lan_ip} ${PORT} || true'

If booth says SSE disconnected:
  curl -i http://localhost:${PORT}/health
  curl -N http://localhost:${PORT}/events
  tail -80 ${SERVER_LOG}

EOF
}

local_property() {
  local key="$1"
  local file="${ROOT_DIR}/local.properties"

  [[ -f "${file}" ]] || return 0
  while IFS='=' read -r prop_key prop_value; do
    [[ "${prop_key}" == "${key}" ]] || continue
    printf '%s' "${prop_value}"
    return 0
  done < "${file}"
}

export_env_from_local_properties() {
  export PORT="${PORT:-${MBA_SERVER_PORT:-8080}}"
  export GEMINI_API_KEY="${GEMINI_API_KEY:-$(local_property GEMINI_API_KEY)}"
  export NOTION_API_KEY="${NOTION_API_KEY:-$(local_property NOTION_TOKEN)}"
  export NOTION_DATABASE_ID="${NOTION_DATABASE_ID:-$(local_property NOTION_CRASH_DB_ID_OR_URL)}"
  export MBA_SERVER_API_KEY="${MBA_SERVER_API_KEY:-$(local_property MBA_SERVER_API_KEY)}"
  export GITHUB_TOKEN="${GITHUB_TOKEN:-$(local_property GITHUB_TOKEN)}"
  export GITHUB_OWNER="${GITHUB_OWNER:-$(local_property GITHUB_OWNER)}"
  export GITHUB_REPO="${GITHUB_REPO:-$(local_property GITHUB_REPO)}"
  export GITHUB_BASE_BRANCH="${GITHUB_BASE_BRANCH:-$(local_property GITHUB_BASE_BRANCH)}"
  export GITHUB_BASE_BRANCH="${GITHUB_BASE_BRANCH:-main}"
}

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

if [[ "${COMMAND}" == "help" || "${COMMAND}" == "--help" || "${COMMAND}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ "${COMMAND}" == "commands" ]]; then
  print_operator_commands
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "❌ curl is required but not installed."
  exit 1
fi

if [[ "${COMMAND}" == "local" ]] && ! command -v adb >/dev/null 2>&1; then
  echo "❌ adb is required but not found in PATH."
  exit 1
fi

mkdir -p "${LOG_DIR}"

package_server() {
  echo "📦 Building deployable server distribution..."
  (
    cd "${ROOT_DIR}"
    "${GRADLEW}" :mba-server:installDist :mba-server:distTar
  )
  echo "✅ Distribution ready: ${ROOT_DIR}/mba-server/build/distributions/mba-server.tar"
  echo "✅ Installed runner: ${INSTALLED_SERVER}"
  print_operator_commands
}

start_gradle_server() {
  echo "🚀 Starting Ktor server (:mba-server:run) on port ${PORT}..."
  echo "🪵 Server log: ${SERVER_LOG}"
  : > "${SERVER_LOG}"
  (
    cd "${ROOT_DIR}"
    "${GRADLEW}" :mba-server:run 2>&1
  ) | tee "${SERVER_LOG}" &
  SERVER_PID=$!
}

start_installed_server() {
  package_server
  export_env_from_local_properties

  echo "🚀 Starting packaged mba-server on port ${PORT}..."
  echo "🪵 Server log: ${SERVER_LOG}"
  : > "${SERVER_LOG}"
  "${INSTALLED_SERVER}" 2>&1 | tee "${SERVER_LOG}" &
  SERVER_PID=$!
}

# Preflight — make sure no stale JVM is holding the port
free_port

if [[ "${COMMAND}" == "package" ]]; then
  package_server
  exit 0
fi

if [[ "${COMMAND}" == "deploy-local" ]]; then
  start_installed_server
elif [[ "${COMMAND}" == "local" || "${COMMAND}" == "server" ]]; then
  start_gradle_server
else
  echo "❌ Unknown command: ${COMMAND}"
  usage
  exit 1
fi

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
  echo "🪵 Last server log lines:"
  tail -80 "${SERVER_LOG}" || true
  exit 1
fi

echo "🌐 Booth dashboard: http://localhost:${PORT}/booth"
echo "🛠️  Operator dashboard: http://localhost:${PORT}/booth?debug=1"
echo "📬 Crash ingest endpoint: http://localhost:${PORT}/report"
echo "📱 Physical-device ingest endpoint: http://$(detect_lan_ip):${PORT}/report"
echo "🪵 Logs now: tail -f ${SERVER_LOG}"

if [[ "${COMMAND}" == "server" || "${COMMAND}" == "deploy-local" ]]; then
  print_operator_commands
  echo "🎉 Server is running. Press Ctrl+C to stop."
  wait "${SERVER_PID}"
  exit 0
fi

echo "📦 Installing Android sample app (:mba-sample:installDebug)..."
export MBA_SAMPLE_BACKEND_ENDPOINT="$(sample_backend_endpoint)"
echo "📡 Sample backend endpoint: ${MBA_SAMPLE_BACKEND_ENDPOINT}"
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

print_operator_commands
echo "🎉 Server + app flow complete. Open http://localhost:${PORT}/booth in your browser. Press Ctrl+C to stop the server."
wait "${SERVER_PID}"
