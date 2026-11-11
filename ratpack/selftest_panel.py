#!/usr/bin/env python3
"""
Panel smoke test.

Exercises every route of the web panel and fails on any non-2xx/3xx
response that is not the expected one. This exists because two undefined
names (resp, request) shipped in the panel and broke the pages at runtime
while the module still imported cleanly.

Usage:
    python3 -m ratpack.selftest_panel [base_url] [user] [pass]
"""
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASS = sys.argv[3] if len(sys.argv) > 3 else "testpass"

jar = http.cookiejar.CookieJar()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keeps redirects visible so the test can assert on them."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(jar), NoRedirect)

FAILURES = []
CHECKS = 0


def check(label, got, want):
    global CHECKS
    CHECKS += 1
    if got == want:
        print(f"  PASS  {label} ({got})")
    else:
        print(f"  FAIL  {label}: got {got}, want {want}")
        FAILURES.append(label)


def get(path, headers=None):
    """Returns (status, body). Redirects are not followed."""
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with opener.open(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def post(path, data, headers=None):
    body = urllib.parse.urlencode(data).encode()
    hdrs = {"Content-Type": "application/x-www-form-urlencoded"}
    hdrs.update(headers or {})
    req = urllib.request.Request(BASE + path, data=body, headers=hdrs, method="POST")
    try:
        with opener.open(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    print("=== unauthenticated ===")
    # A browser must be sent to the login page, not shown a JSON error.
    for path in ("/", "/bot/x", "/setting.json", "/api/bots"):
        st, body = get(path)
        check(f"{path} redirects to login", st in (302, 303, 307, 308), True)

    # An API client must get JSON, not a redirect.
    st, body = get("/api/bots", headers={"Accept": "application/json"})
    check("API gets 401 JSON", st, 401)
    check("API body is JSON", "authentication" in body.lower(), True)

    st, _ = get("/login")
    check("login page", st, 200)

    print("\n=== login ===")
    st, _ = post("/login", {"username": USER, "password": "definitely-wrong"})
    check("wrong password -> 401", st, 401)

    st, _ = post("/login", {"username": USER, "password": PASS})
    check("correct password -> redirect", st in (302, 303, 307), True)

    print("\n=== authenticated ===")
    for path, want in (("/", 200), ("/setting.json", 200), ("/api/bots", 200),
                       ("/healthz", 200), ("/login", 302)):
        st, body = get(path)
        check(f"GET {path}", st, want)

    st, body = get("/")
    check("index renders a table", "<table>" in body, True)
    check("index loads the css", "panel.css" in body, True)

    st, body = get("/api/bots")
    check("api/bots is JSON", body.strip().startswith("{"), True)
    try:
        bots = json.loads(body).get("bots", [])
    except Exception:
        bots = []
    check("api/bots parses", isinstance(bots, list), True)

    if bots:
        bid = bots[0]["id"]
        st, _ = get(f"/bot/{bid}")
        check("bot page", st, 200)
        st, _ = get(f"/bot/{bid}?cmd=info")
        check("bot page with a command", st, 200)

    st, body = get("/setting.json")
    check("setting.json is JSON", body.strip().startswith("{"), True)
    try:
        doc = json.loads(body)
        check("setting.json has a server block", "server" in doc, True)
    except Exception:
        check("setting.json parses", False, True)

    print("\n=== logout ===")
    st, _ = get("/logout")
    check("logout redirects", st in (302, 303, 307), True)
    st, _ = get("/")
    check("after logout -> login", st in (302, 303, 307), True)

    print()
    print(f"{CHECKS - len(FAILURES)}/{CHECKS} checks passed")
    if FAILURES:
        print("FAILED:", ", ".join(FAILURES))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
