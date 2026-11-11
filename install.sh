#!/usr/bin/env bash
#
# RatPack server installer — one line:
#
#   curl -fsSL https://raw.githubusercontent.com/AGlegendery/RatPack/server/install.sh | bash
#
# It clones the `server` branch, creates a virtualenv, installs the
# dependencies, runs the setup wizard, writes setting.json and (as root)
# registers the C2 as a systemd service.
#
# Options (pass after --):
#   --dir PATH        install directory      (default: ~/RatPack-Server)
#   --no-systemd      skip the systemd unit
#   --no-start        do not start the service
#   --branch NAME     branch to install      (default: server)
#   -h | --help       show this help
#
# Environment:
#   RATPACK_ANSWERS   answers for the wizard, either inline text or the
#                     path to a file. Used for unattended installs; when it
#                     is unset the wizard reads from the terminal.
#
set -euo pipefail

BRANCH="server"
INSTALL_DIR="${HOME}/RatPack-Server"
DO_SYSTEMD=1
DO_START=1

REPO="https://github.com/AGlegendery/RatPack.git"

usage() {
  cat <<'USAGE'
RatPack server installer — one line:

  curl -fsSL https://raw.githubusercontent.com/AGlegendery/RatPack/server/install.sh | bash

It clones the `server` branch, sets up a virtualenv, installs the
dependencies, runs the setup wizard and (optionally) registers the C2 as a
systemd service.

Options (pass after --):
  --dir PATH        install directory      (default: ~/RatPack-Server)
  --no-systemd      skip the systemd unit
  --no-start        do not start the service
  --branch NAME     branch to install      (default: server)
  -h | --help       show this help

Environment:
  RATPACK_ANSWERS   wizard answers (inline text or a file path) for
                    unattended installs
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir)        INSTALL_DIR="$2"; shift 2 ;;
    --branch)     BRANCH="$2"; shift 2 ;;
    --no-systemd) DO_SYSTEMD=0; shift ;;
    --no-start)   DO_START=0; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

C_G=''; C_Y=''; C_R=''; C_B=''; C_0=''
if [[ -t 1 ]]; then
  C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_B=$'\033[1m'; C_0=$'\033[0m'
fi

say()  { echo "${C_B}[*]${C_0} $*"; }
ok()   { echo "${C_G}[+]${C_0} $*"; }
warn() { echo "${C_Y}[!]${C_0} $*"; }
die()  { echo "${C_R}[x]${C_0} $*" >&2; exit 1; }

echo
echo "${C_B}=========================================================${C_0}"
echo "${C_B}  RatPack server installer${C_0}"
echo "${C_B}=========================================================${C_0}"
echo

say "checking prerequisites"

command -v python3 >/dev/null 2>&1 || die "python3 not found. Install Python 3.9+ first."
python3 -c 'import sys; sys.exit(0 if sys.version_info[:2] >= (3,9) else 1)' \
  || die "python3 is older than 3.9"
ok "python3 $(python3 -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])')"

command -v git >/dev/null 2>&1 || die "git not found. Install git first."
ok "git $(git --version | awk '{print $3}')"

python3 -c 'import venv' >/dev/null 2>&1 \
  || die "the venv module is missing. Install it:  (debian/ubuntu) apt install python3-venv   (fedora) dnf install python3-virtualenv   (arch) pacman -S python"
ok "venv module available"

say "installing into $INSTALL_DIR"

if [[ -d "$INSTALL_DIR/.git" ]]; then
  ok "existing checkout found, updating"
  git -C "$INSTALL_DIR" fetch --quiet origin "$BRANCH"
  git -C "$INSTALL_DIR" checkout --quiet "$BRANCH"
  git -C "$INSTALL_DIR" reset --hard "origin/$BRANCH"
elif [[ -d "$INSTALL_DIR" ]] && [[ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ]]; then
  say "target directory exists, cloning into it"
  TMP_CLONE="$(mktemp -d "${INSTALL_DIR}.XXXXXX")"
  git clone --quiet --branch "$BRANCH" --depth 1 "$REPO" "$TMP_CLONE" \
    || die "clone failed. If the branch is private, use a token:  git clone -b $BRANCH https://<token>@github.com/AGlegendery/RatPack.git $INSTALL_DIR"
  rm -rf "${INSTALL_DIR:?}"
  mv "$TMP_CLONE" "$INSTALL_DIR"
  ok "cloned branch '$BRANCH'"
else
  mkdir -p "$(dirname "$INSTALL_DIR")"
  git clone --quiet --branch "$BRANCH" --depth 1 "$REPO" "$INSTALL_DIR" \
    || die "clone failed. If the branch is private, use a token:  git clone -b $BRANCH https://<token>@github.com/AGlegendery/RatPack.git $INSTALL_DIR"
  ok "cloned branch '$BRANCH'"
fi

cd "$INSTALL_DIR"

if [[ ! -x venv/bin/python ]]; then
  say "creating the virtualenv"
  python3 -m venv venv || die "could not create the virtualenv"
fi
ok "virtualenv at $INSTALL_DIR/venv"

say "installing dependencies (this can take a minute)"
./venv/bin/python -m pip install --quiet --upgrade pip setuptools wheel \
  || warn "pip self-upgrade failed, continuing"
./venv/bin/python -m pip install --quiet -r requirements.txt \
  || die "dependency installation failed"
ok "dependencies installed"

# The wizard must not read stdin when this script was piped in: stdin still
# holds the tail of the installer source, and the wizard would consume
# script lines as answers. Read from the terminal when there is one,
# otherwise from RATPACK_ANSWERS.
WIZARD_IN=""
if [[ -n "${RATPACK_ANSWERS:-}" ]]; then
  if [[ -f "$RATPACK_ANSWERS" ]]; then
    WIZARD_IN="$RATPACK_ANSWERS"
  else
    WIZARD_IN="$(mktemp)"
    printf '%s\n' "$RATPACK_ANSWERS" > "$WIZARD_IN"
  fi
  say "using RATPACK_ANSWERS for the wizard"
elif [[ -r /dev/tty ]]; then
  say "starting the setup wizard (answers are read from the terminal)"
else
  WIZARD_IN="$(mktemp)"
  warn "no terminal available. Set RATPACK_ANSWERS to answer the wizard unattended."
fi

if [[ -f panel_config.json ]]; then
  warn "panel_config.json already exists, keeping it"
  say "re-running the wizard (your current answers are the defaults)"
fi

echo
if [[ -n "$WIZARD_IN" ]]; then
  ./venv/bin/python setup.py < "$WIZARD_IN" || die "the setup wizard failed"
else
  # The wizard opens /dev/tty itself, so stdin can stay empty.
  ./venv/bin/python setup.py < /dev/null || die "the setup wizard failed"
fi
echo

say "writing setting.json for the compiler"
./venv/bin/python - <<'PY' || warn "could not write setting.json (the panel can still serve it)"
import json
from ratpack import config

cfg = config.load()
doc = config.build_setting_json(cfg)
with open("setting.json", "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(f"    server.host     = {doc['server']['host']}")
print(f"    server.port     = {doc['server']['port']}")
print(f"    server.protocol = {doc['server']['protocol']}")
PY
ok "setting.json written to $INSTALL_DIR/setting.json"

if [[ "$DO_SYSTEMD" -eq 1 ]]; then
  if command -v systemctl >/dev/null 2>&1 && [[ "$(id -u)" -eq 0 ]]; then
    UNIT=/etc/systemd/system/ratpack.service
    say "installing the systemd unit"
    cat > "$UNIT" <<UNIT
[Unit]
Description=RatPack C2 server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/venv/bin/python -m ratpack.run
Restart=on-failure
RestartSec=5
StandardOutput=append:/var/log/ratpack.log
StandardError=append:/var/log/ratpack.log

[Install]
WantedBy=multi-user.target
UNIT
    systemctl daemon-reload
    systemctl enable ratpack.service >/dev/null 2>&1 || true
    ok "systemd unit installed (ratpack.service)"
    if [[ "$DO_START" -eq 1 ]]; then
      systemctl restart ratpack.service || warn "systemctl restart failed, check: journalctl -u ratpack -n 50"
      sleep 2
      systemctl is-active --quiet ratpack.service \
        && ok "service is running" \
        || warn "service is not active; check: journalctl -u ratpack -n 50"
    fi
  else
    warn "systemd unit skipped (needs root). Start manually:  cd $INSTALL_DIR && ./venv/bin/python -m ratpack.run"
  fi
elif [[ "$DO_START" -eq 1 ]]; then
  warn "no systemd unit; start manually:  cd $INSTALL_DIR && ./venv/bin/python -m ratpack.run"
fi

cat <<SUMMARY

${C_B}=========================================================${C_0}
${C_B}  RatPack server is installed${C_0}
${C_B}=========================================================${C_0}

  install dir  : $INSTALL_DIR
  config       : $INSTALL_DIR/panel_config.json
  setting.json : $INSTALL_DIR/setting.json

${C_B}  Get the configuration for the compiler:${C_0}

    cat $INSTALL_DIR/setting.json

  or over HTTP (needs the panel login):

    curl -c c.txt -X POST -d "username=USER&password=PASS" http://HOST:PORT/login
    curl -b c.txt -o setting.json http://HOST:PORT/setting.json

${C_B}  Manage the service:${C_0}

    systemctl status ratpack
    systemctl restart ratpack
    journalctl -u ratpack -f

${C_B}  Send that setting.json back and the APK gets compiled with it.${C_0}

SUMMARY
