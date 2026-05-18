#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${ROOT_DIR}/gradlew"
DEFAULT_VERSION="0.1.0-kotlinconf-SNAPSHOT"

usage() {
  cat <<'EOF'
Usage:
  scripts/publish-sdk.sh local [version]
      Publish SDK modules to ~/.m2/repository.

  scripts/publish-sdk.sh check-local [version]
      Publish to Maven local, then compile mba-sample against Maven artifacts.

  scripts/publish-sdk.sh github [version]
      Publish SDK modules to GitHub Packages.

Environment / properties:
  MBA_VERSION                Optional version fallback.
  GITHUB_ACTOR              GitHub Packages username for github publish.
  GITHUB_TOKEN or GH_TOKEN   Token with package write permission.

Examples:
  scripts/publish-sdk.sh local 0.1.0-local
  scripts/publish-sdk.sh check-local 0.1.0-local
  GITHUB_ACTOR=sunnat629 GITHUB_TOKEN=... scripts/publish-sdk.sh github 0.1.0-kotlinconf.1
EOF
}

version_arg() {
  local provided="${1:-}"
  if [[ -n "${provided}" ]]; then
    echo "${provided}"
  elif [[ -n "${MBA_VERSION:-}" ]]; then
    echo "${MBA_VERSION}"
  else
    echo "${DEFAULT_VERSION}"
  fi
}

require_gradlew() {
  if [[ ! -x "${GRADLEW}" ]]; then
    echo "gradlew not found or not executable at ${GRADLEW}" >&2
    exit 1
  fi
}

publish_local() {
  local version="$1"
  "${GRADLEW}" publishToMavenLocal -PMBA_VERSION="${version}"
}

check_local() {
  local version="$1"
  publish_local "${version}"
  "${GRADLEW}" :mba-sample:compileDebugKotlin \
    -PMBA_USE_MAVEN_LOCAL=true \
    -PMBA_SAMPLE_USE_PUBLISHED_SDK=true \
    -PMBA_SAMPLE_SDK_VERSION="${version}"
}

publish_github() {
  local version="$1"
  if [[ -z "${GITHUB_ACTOR:-}" ]]; then
    echo "GITHUB_ACTOR is required for GitHub Packages publishing." >&2
    exit 1
  fi
  if [[ -z "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
    echo "GITHUB_TOKEN or GH_TOKEN is required for GitHub Packages publishing." >&2
    exit 1
  fi
  "${GRADLEW}" publish -PMBA_VERSION="${version}"
}

main() {
  require_gradlew

  local command="${1:-}"
  local version
  version="$(version_arg "${2:-}")"

  case "${command}" in
    local)
      publish_local "${version}"
      ;;
    check-local)
      check_local "${version}"
      ;;
    github)
      publish_github "${version}"
      ;;
    -h|--help|help|"")
      usage
      ;;
    *)
      echo "Unknown command: ${command}" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "$@"
