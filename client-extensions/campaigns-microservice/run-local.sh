#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-local.sh – Build and run the Campaigns microservice locally
#
# Usage:
#   ./run-local.sh              Build the bootJar and start the Spring Boot app
#   ./run-local.sh --skip-build Start from an already-built jar (faster restart)
#   ./run-local.sh --stop       Gracefully stop a running instance (uses .pid file)
#
# Environment variables can be supplied via a .env file in this directory.
# See .env.example for the expected keys.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PID_FILE="${SCRIPT_DIR}/.campaigns.pid"

# ── Handle --stop early (no .env / build needed) ────────────────────────────
for arg in "$@"; do
    if [[ "${arg}" == "--stop" ]]; then
        if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
            pid="$(cat "${PID_FILE}")"
            echo "🛑 Stopping campaigns-microservice (PID ${pid}) …"
            kill -TERM "${pid}"
            rm -f "${PID_FILE}"
            echo "✅ Stopped."
        else
            echo "ℹ️  No running instance found (no live PID in ${PID_FILE})."
            rm -f "${PID_FILE}" 2>/dev/null || true
        fi
        exit 0
    fi
done

# ── Load .env file if present ───────────────────────────────────────────────
if [[ -f "${SCRIPT_DIR}/.env" ]]; then
    echo "📄 Loading environment from ${SCRIPT_DIR}/.env"
    set -a
    # shellcheck disable=SC1091
    source "${SCRIPT_DIR}/.env"
    set +a
fi

# ── Warn about credentials that gate optional features ──────────────────────
#
# The service boots and answers /ready without any credentials, but two
# features stay disabled until their secrets are supplied:
#   - Campaign scheduler: skipped unless LIFERAY_EMAIL and LIFERAY_PASSWORD set
#   - Outbound email:      sends fail to authenticate unless SPRING_MAIL_PASSWORD set
# These are warnings, not hard errors, so the service can still be started for
# local probing or tracking-pixel work.
# ---------------------------------------------------------------------------
if [[ -z "${LIFERAY_EMAIL:-}" || -z "${LIFERAY_PASSWORD:-}" ]]; then
    echo "⚠️  LIFERAY_EMAIL / LIFERAY_PASSWORD not both set — campaign scheduler will be skipped."
fi
if [[ -z "${SPRING_MAIL_PASSWORD:-}" ]]; then
    echo "⚠️  SPRING_MAIL_PASSWORD not set — outbound email will fail to authenticate."
fi

# ── Create LXC configtree directories ───────────────────────────────────────
#
# On Liferay Cloud (LCP), the Spring Boot client extension library reads
# properties from "configtree" directories mounted by the platform:
#
#   - /etc/liferay/lxc/dxp-metadata      → DXP connection properties
#   - /etc/liferay/lxc/ext-init-metadata  → client extension OAuth metadata
#
# Each file in these directories becomes a Spring property where the filename
# is the property key and the file content is the value. When running locally,
# these directories do not exist, so the script creates them in a temporary
# location and points the env vars accordingly.
# ---------------------------------------------------------------------------

LIFERAY_DXP_HOST="${LIFERAY_DXP_HOST:-webserver-lctfootrial-prd.lfr.cloud}"
LIFERAY_DXP_PROTOCOL="${LIFERAY_DXP_PROTOCOL:-https}"

CONFIGTREE_DIR="${SCRIPT_DIR}/.configtree"
DXP_METADATA_DIR="${CONFIGTREE_DIR}/dxp-metadata"
EXT_METADATA_DIR="${CONFIGTREE_DIR}/ext-init-metadata"

mkdir -p "${DXP_METADATA_DIR}" "${EXT_METADATA_DIR}"

# DXP metadata – consumed by LiferayOAuth2ResourceServerEnableWebSecurity
# and LiferayOAuth2ClientConfiguration via @Value annotations
printf '%s' "${LIFERAY_DXP_HOST}"     > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.domains"
printf '%s' "${LIFERAY_DXP_HOST}"     > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.mainDomain"
printf '%s' "${LIFERAY_DXP_PROTOCOL}" > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.server.protocol"

echo "📂 Created LXC configtree at ${CONFIGTREE_DIR}"
echo "   DXP domain:   ${LIFERAY_DXP_PROTOCOL}://${LIFERAY_DXP_HOST}"

# Point the env vars that application.properties references
export LIFERAY_ROUTES_DXP="${DXP_METADATA_DIR}"
export LIFERAY_ROUTES_CLIENT_EXTENSION="${EXT_METADATA_DIR}"

# ── Pre-flight: verify configured hosts actually resolve ────────────────────
#
# The OAuth2 JWK decoder fetches signing keys from LIFERAY_DXP_HOST during
# Spring context startup. If that host is unresolvable the app dies with a
# ~200-line bean-creation stack trace whose real cause (UnknownHostException)
# is buried at the very bottom. We check every configured host up front and
# fail fast with a readable summary: ✓ resolves, ⚠ optional/degraded, ✗ fatal.
# ---------------------------------------------------------------------------

# Resolve a bare hostname (no scheme/port). Returns 0 if it resolves.
resolve_host() {
    local host="$1"
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "import socket,sys; socket.gethostbyname(sys.argv[1])" "${host}" >/dev/null 2>&1
    else
        nslookup "${host}" >/dev/null 2>&1
    fi
}

# Strip scheme, path, and port from a URL or host:port string → bare hostname.
url_host() {
    local v="$1"
    v="${v#*://}"   # drop scheme://
    v="${v%%/*}"    # drop /path
    v="${v%%:*}"    # drop :port
    printf '%s' "${v}"
}

PREFLIGHT_FAIL=0

# check_var NAME VALUE SEVERITY(critical|optional)
check_var() {
    local name="$1" raw="$2" severity="$3" host
    if [[ -z "${raw}" ]]; then
        printf '   ⏭  %-30s (unset, skipped)\n' "${name}"
        return
    fi
    host="$(url_host "${raw}")"
    if resolve_host "${host}"; then
        printf '   ✓  %-30s %s\n' "${name}" "${host}"
    elif [[ "${severity}" == "critical" ]]; then
        printf '   ✗  %-30s %s  (UNRESOLVABLE — required to boot)\n' "${name}" "${host}"
        PREFLIGHT_FAIL=1
    else
        printf '   ⚠  %-30s %s  (unresolvable — that feature will be degraded)\n' "${name}" "${host}"
    fi
}

echo "🔎 Pre-flight: resolving configured hosts …"
check_var LIFERAY_DXP_HOST              "${LIFERAY_DXP_HOST}"                    critical
check_var LIFERAY_BASE_URL              "${LIFERAY_BASE_URL:-}"                  optional
check_var LIFERAY_HEADLESS_API_BASE_URL "${LIFERAY_HEADLESS_API_BASE_URL:-}"     optional
check_var EMAIL_FRIENDLY_URL_BASE_URL   "${EMAIL_FRIENDLY_URL_BASE_URL:-}"       optional
check_var TRACKING_PIXEL_BASE_URL       "${TRACKING_PIXEL_BASE_URL:-}"           optional
check_var ANALYTICS_ENDPOINT_URL        "${ANALYTICS_ENDPOINT_URL:-}"            optional
check_var SPRING_MAIL_HOST              "${SPRING_MAIL_HOST:-}"                  optional

if [[ "${PREFLIGHT_FAIL}" -ne 0 ]]; then
    echo ""
    echo "❌ A required host (✗ above) does not resolve, so the app cannot boot."
    echo "   The OAuth2 JWK decoder fetches keys from LIFERAY_DXP_HOST at startup."
    echo "   Fix it in ${SCRIPT_DIR}/.env, e.g. for local dev:"
    echo "       LIFERAY_DXP_HOST=localhost:8080"
    echo "       LIFERAY_DXP_PROTOCOL=http"
    echo "   then re-run. (Skipping the build since it would fail anyway.)"
    exit 1
fi
echo ""

# ── Resolve Gradle wrapper ──────────────────────────────────────────────────
GRADLEW="${PROJECT_ROOT}/gradlew"
if [[ ! -x "${GRADLEW}" ]]; then
    echo "❌ Gradle wrapper not found or not executable at ${GRADLEW}"
    exit 1
fi

# ── Build (unless --skip-build) ─────────────────────────────────────────────
SKIP_BUILD=false
for arg in "$@"; do
    [[ "${arg}" == "--skip-build" ]] && SKIP_BUILD=true
done

if [[ "${SKIP_BUILD}" == false ]]; then
    echo "🔨 Building campaigns-microservice bootJar …"
    "${GRADLEW}" -p "${PROJECT_ROOT}" :client-extensions:campaigns-microservice:bootJar
    echo ""
fi

# ── Locate the boot jar ─────────────────────────────────────────────────────
BUILD_DIR="${SCRIPT_DIR}/build/libs"
BOOT_JAR=$(find "${BUILD_DIR}" -maxdepth 1 -name "*.jar" -type f 2>/dev/null | head -1)

if [[ -z "${BOOT_JAR}" ]]; then
    echo "❌ No jar found in ${BUILD_DIR}. Run without --skip-build first."
    exit 1
fi

echo ""
echo "🚀 Starting campaigns-microservice from: $(basename "${BOOT_JAR}")"
echo "   Port:  58081"
echo "   Ready: http://localhost:58081/ready"
echo "   Stop:  Ctrl-C, or ./run-local.sh --stop"
echo ""

# ── Run the Spring Boot application ─────────────────────────────────────────
#
# Launched in the background (not exec'd) so we can record a PID file and
# install a trap that forwards SIGINT/SIGTERM for a clean Spring shutdown —
# whether stopped via Ctrl-C in the foreground or `./run-local.sh --stop`.
# ---------------------------------------------------------------------------
APP_PID=""
shutdown() {
    echo ""
    echo "🛑 Shutting down campaigns-microservice (PID ${APP_PID}) …"
    [[ -n "${APP_PID}" ]] && kill -TERM "${APP_PID}" 2>/dev/null || true
    [[ -n "${APP_PID}" ]] && wait "${APP_PID}" 2>/dev/null || true
    rm -f "${PID_FILE}"
    echo "✅ Stopped."
}
trap shutdown INT TERM

java \
    -XX:MaxRAMPercentage=50.0 \
    -Dspring.profiles.active=default \
    -jar "${BOOT_JAR}" &
APP_PID=$!
printf '%s' "${APP_PID}" > "${PID_FILE}"

# Wait on the app; when it exits (or a signal triggers the trap), clean up.
wait "${APP_PID}"
rm -f "${PID_FILE}"
