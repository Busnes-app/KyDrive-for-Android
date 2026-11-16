#!/usr/bin/env bash
# A throwaway KyDrive server for scripts/screenshots.sh, reachable from the emulator over
# HTTPS at 10.0.2.2:<free port> (socat in front of the server, with a CA made for this run).
#   screenshot-server.sh start DIR   starts it, seeds files, writes DIR/ca.pem; prints nothing
#   screenshot-server.sh link DIR    prints a fresh one-time kydrive://link
#   screenshot-server.sh stop DIR    stops it
# Needs KYDRIVE_SERVER_BIN, openssl, socat, curl and python3. Development mode, relays off.
set -euo pipefail
cmd=${1:?start, link or stop}
dir=${2:?state directory}
free() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
[ "$cmd" = start ] && { mkdir -p "$dir"; free > "$dir/tls_port"; }
tls=$(cat "$dir/tls_port")
origin=https://10.0.2.2:$tls

# The emulator's host alias does not route here; curl reaches it on loopback.
web() {
  local method=$1 path=$2
  shift 2
  curl -sS --fail-with-body --cacert "$dir/ca.pem" --connect-to "10.0.2.2:$tls:127.0.0.1:$tls" \
    -b "$dir/jar" -c "$dir/jar" -X "$method" \
    -H "X-CSRF-Token: $(awk '$6 == "ky_csrf" { print $7 }' "$dir/jar" 2>/dev/null)" "$@" "$origin$path"
}
json() { python3 -I -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

case $cmd in
start)
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj /CN=kydrive-screenshots-ca \
    -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign \
    -keyout "$dir/ca.key" -out "$dir/ca.pem" 2> /dev/null
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj /CN=10.0.2.2 \
    -keyout "$dir/leaf.key" -out "$dir/leaf.csr" 2> /dev/null
  printf 'subjectAltName=IP:10.0.2.2\nextendedKeyUsage=serverAuth\n' > "$dir/leaf.ext"
  openssl x509 -req -in "$dir/leaf.csr" -CA "$dir/ca.pem" -CAkey "$dir/ca.key" -CAcreateserial \
    -days 2 -extfile "$dir/leaf.ext" -out "$dir/leaf.pem" 2> /dev/null
  port=$(free)
  scim=screenshots-scim-token-0123456789abcdef
  KY_ENV=development KY_CAPTCHA_PROVIDER=none KY_DB_DRIVER=sqlite KY_DATA_DIR="$dir/data" \
    KY_BACKUP_DIR="$dir/backups" KY_HOST=127.0.0.1 KY_PORT="$port" KY_APP_URL="$origin" \
    KY_ADMIN_PASSWORD=ShotsBootstrapPass123! KY_SCIM_ENABLED=true KY_SCIM_TOKEN="$scim" \
    PUSH_RELAY_URL='' APNS_RELAY_URL='' "${KYDRIVE_SERVER_BIN:?}" > "$dir/server.log" 2>&1 &
  echo $! > "$dir/server.pid"
  socat "OPENSSL-LISTEN:$tls,reuseaddr,fork,cert=$dir/leaf.pem,key=$dir/leaf.key,verify=0" "TCP:127.0.0.1:$port" > "$dir/socat.log" 2>&1 < /dev/null &
  echo $! > "$dir/socat.pid"
  for _ in $(seq 100); do
    curl -fsS -o /dev/null "http://127.0.0.1:$port/healthz" 2> /dev/null && break
    sleep 0.2
  done
  # The bootstrap password must be replaced before the session is useful.
  web POST /api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"ShotsBootstrapPass123!"}' > /dev/null
  web POST /api/auth/change-password -H 'Content-Type: application/json' -d '{"current_password":"ShotsBootstrapPass123!","new_password":"ShotsReplacementPass456!"}' > /dev/null
  web POST /api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"ShotsReplacementPass456!"}' > /dev/null
  me=$(web GET /api/auth/me | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["user"]["id"])')

  # A directory group holding the admin carries the shared workspaces' grants.
  group=$(curl -fsS -X POST "http://127.0.0.1:$port/scim/v2/Groups" -H "Authorization: Bearer $scim" \
    -H 'Content-Type: application/scim+json' \
    -d "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\"Staff\",\"members\":[{\"value\":\"$me\"}]}" | json id)
  folder() { web POST "/api/drive/workspaces/$1/folders" -H 'Content-Type: application/json' -d "{\"parent\":\"$2\",\"name\":\"$3\"}" | json id; }
  file() {
    local name
    name=$(python3 -I -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$3")
    head -c "$4" /dev/urandom | web POST "/api/drive/workspaces/$1/uploads?parent=$2&name=$name&revision=0" --data-binary @- | json id
  }
  home=$(web POST /api/drive/personal-workspace | json id)
  projects=$(folder "$home" "" Projects)
  folder "$home" "" Photos > /dev/null
  folder "$home" "" Receipts > /dev/null
  file "$home" "" "Budget 2026.xlsx" 48213 > /dev/null
  file "$home" "" "Notes.txt" 1840 > /dev/null
  file "$home" "" "Lease agreement.pdf" 812345 > /dev/null
  file "$home" "$projects" "Proposal.pdf" 1530221 > /dev/null
  file "$home" "$projects" "Roadmap.md" 6120 > /dev/null
  file "$home" "$projects" "Site photo.jpg" 2411876 > /dev/null
  old=$(file "$home" "" "Old draft.docx" 22400)
  web PUT "/api/drive/files/$old/trash" -H 'Content-Type: application/json' -d '{"revision":1,"trashed":true}' > /dev/null
  for ws in "Team:editor" "Finance:reader"; do
    id=$(web POST /api/drive/workspaces -H 'Content-Type: application/json' -d "{\"name\":\"${ws%%:*}\",\"quota\":1073741824}" | json id)
    # Edit rights to seed the file, then the role the screenshots show.
    grant() { web PUT "/api/drive/workspaces/$id/grants/$group" -H 'Content-Type: application/json' -d "{\"role\":\"$1\"}" > /dev/null; }
    grant editor
    file "$id" "" "Shared plan.pdf" 304112 > /dev/null
    [ "${ws##*:}" = editor ] || grant "${ws##*:}"
  done
  ;;
link)
  web POST /api/devices/phone-code | json link
  ;;
stop)
  for p in server socat; do
    [ -f "$dir/$p.pid" ] && kill "$(cat "$dir/$p.pid")" 2> /dev/null || true
  done
  ;;
esac
