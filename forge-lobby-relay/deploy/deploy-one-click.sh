#!/usr/bin/env bash
# New dedicated Linux host only. Never overwrites a running deployment.
set -euo pipefail
cd -- "$(dirname -- "$0")"
package_dir="$(pwd -P)"
fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
    printf '%s\n' "Preview: bash deploy-one-click.sh --domain relay.example.com --email you@example.com [--port 443]" \
      "Install: add --apply (root, Docker Engine + Compose v2 + systemd required)" \
      "Renew: bash deploy-one-click.sh --renew" \
      "Never use --apply to upgrade an existing live deployment."
}
domain="" email="" port=443 apply=false renew=false
while (($#)); do
    case "$1" in
        --domain|--email|--port)
            (($# >= 2)) || fail "Missing value: $1"
            case "$1" in --domain) domain="$2";; --email) email="$2";; --port) port="$2";; esac
            shift 2 ;;
        --apply) apply=true; shift ;;
        --renew) renew=true; shift ;;
        --help) usage; exit 0 ;;
        *) usage; fail "Unknown option: $1" ;;
    esac
done
[[ "$package_dir" =~ ^/[a-zA-Z0-9_./-]+$ ]] || fail "Use an absolute package path without spaces, e.g. /opt/forge-relay-bundle."
compose() { docker compose --env-file state/settings.env -f compose.yaml "$@"; }
if "$renew"; then
    [[ -f state/settings.env ]] || fail "No installation settings."
    command -v flock >/dev/null || fail "flock is required"
    exec 9>state/renew.lock
    flock -n 9 || exit 0
    compose run --rm --service-ports certbot renew --non-interactive
    compose exec -T tls nginx -t
    compose exec -T tls nginx -s reload
    exit 0
fi
[[ "${#domain}" -le 253 && "$domain" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ && "$domain" == *.* ]] || fail "Use a lowercase DNS domain, not a URL."
[[ "$domain" != *..* && "$domain" != *.-* && "$domain" != *-.* ]] || fail "Invalid domain."
[[ "$email" =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] || fail "Invalid contact email."
[[ "$port" =~ ^[0-9]{1,5}$ ]] || fail "Invalid TLS port."
port=$((10#$port))
((port >= 1 && port <= 65535 && port != 80 && port != 36744 && port != 36745)) || fail "Invalid/reserved TLS port."
printf '%s\n' "Domain: $domain; TLS port: $port; health: 127.0.0.1:36745" \
    "Requires inbound TCP 80 for certificate issuance/renewal, and TCP $port for clients." \
    "This will accept Let's Encrypt's terms, obtain a certificate using $email, pull container images, start a NEW relay, and install its renewal timer." \
    "No host firewall, existing website, existing relay, or remote community server will be changed."
"$apply" || { printf '%s\n' "Preview only. Review, then repeat with --apply."; exit 0; }
[[ "$EUID" -eq 0 ]] || fail "Run with sudo."
for tool in docker curl ss systemctl flock sha256sum openssl timeout; do command -v "$tool" >/dev/null || fail "Missing prerequisite: $tool"; done
docker compose version >/dev/null
docker info >/dev/null
[[ -f forge-lobby-relay.jar && -f SHA256SUMS ]] || fail "Run from an extracted release bundle, not the source directory."
sha256sum -c SHA256SUMS >/dev/null
[[ ! -e state && ! -e /etc/systemd/system/forge-community-relay-renew.service && ! -e /etc/systemd/system/forge-community-relay-renew.timer ]] || fail "Existing deployment/state found; refusing overwrite."
[[ -z "$(docker ps -aq --filter label=com.docker.compose.project=forge-community-relay)" ]] || fail "Existing relay containers found."
for check_port in 80 "$port" 36744 36745; do
    [[ -z "$(ss -H -ltn "sport = :$check_port")" ]] || fail "Port $check_port is already in use; no services were stopped."
done
umask 077
mkdir state
printf 'FORGE_TLS_PORT=%s\n' "$port" >state/settings.env
sed "s/@DOMAIN@/$domain/g" nginx-container.conf.template >state/nginx.conf
compose config --quiet
compose build relay
compose run --rm --service-ports certbot certonly --standalone --non-interactive \
    --agree-tos --email "$email" --cert-name "$domain" -d "$domain"
compose up -d relay
compose run --rm --no-deps tls nginx -t
compose up -d tls
healthy=false
for attempt in {1..20}; do
    if curl --fail --silent --max-time 2 http://127.0.0.1:36745/health | grep -q '"status":"ok"'; then healthy=true; break; fi
    sleep 1
done
"$healthy" || fail "Health check failed. Kept state and logs; inspect docker compose logs. No automatic destructive rollback."
# TLS handshake check: a healthy Java backend alone does not verify the public endpoint.
tls_ready=false
for attempt in {1..10}; do
    if timeout 5 openssl s_client -connect "127.0.0.1:$port" -servername "$domain" \
        -verify_hostname "$domain" -verify_return_error </dev/null >/dev/null 2>&1; then tls_ready=true; break; fi
    sleep 1
done
"$tls_ready" || fail "TLS validation failed. State retained; inspect TLS container logs and certificate before enabling renewal."
cat >/etc/systemd/system/forge-community-relay-renew.service <<UNIT
[Unit]
Description=Renew self-hosted Forge relay TLS certificate
After=docker.service
[Service]
Type=oneshot
ExecStart=/bin/bash $package_dir/deploy-one-click.sh --renew
UNIT
cat >/etc/systemd/system/forge-community-relay-renew.timer <<'UNIT'
[Unit]
Description=Check Forge relay certificate twice daily
[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now forge-community-relay-renew.timer
printf '%s\n' "Installed: tls://$domain:$port" \
    "Next: verify room creation/joining with TWO clients. Do not treat /health as a full gameplay test." \
    "Keep this package directory and state/ private keys. Never publish state/. Upgrades/restarts disconnect players."
