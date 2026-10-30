import json, os, random, threading, time, uuid, urllib.request
from flask import Flask, render_template, render_template_string, request, jsonify, abort, Response

app = Flask(__name__)
SECRET_PATH = os.environ.get("CONTROL_PATH", "ctrl-7x9k2m")   # secret control page
API_KEY = os.environ.get("API_KEY", "")                        # set to require X-API-Key on /api/v1
WEBHOOK = os.environ.get("WEBHOOK_URL", "")                    # POSTed after every /analyze
STATE_FILE = os.path.join(os.path.dirname(__file__), "state.json")
HISTORY, _lock, _rnd = [], threading.Lock(), {"v": "human", "t": 0}


def load_state():
    try:
        return json.load(open(STATE_FILE))
    except Exception:
        return {"verdict": "human", "confidence": 96}


def save_state(s):
    with _lock:
        json.dump(s, open(STATE_FILE, "w"))


def engine():
    """PLUG A REAL MODEL HERE. Must return (ai_prob 0-100, metrics dict)."""
    s = load_state(); v = s["verdict"]
    if v == "random":
        if time.time() - _rnd["t"] > random.uniform(8, 16):
            _rnd.update(v=random.choice(["human", "ai"]), t=time.time())
        v = _rnd["v"]
    base = max(60, min(99, int(s.get("confidence", 96)))); ai = v == "ai"
    p = random.uniform(base - 6, base + 2) if ai else random.uniform(100 - base - 2, 100 - base + 6)
    j = lambda a, b: round(random.uniform(a, b), 1)
    m = {"spectral": j(72, 94) if ai else j(8, 24), "vocoder": j(70, 93) if ai else j(3, 17),
         "prosody": j(10, 28) if ai else j(76, 95), "breath": j(7, 26) if ai else j(74, 94)}
    return round(max(1, min(99, p)), 1), m


def pack(p, m):
    ai = p >= 50
    return {"verdict": "ai" if ai else "human", "ai_prob": p, "human_prob": round(100 - p, 1),
            "confidence": round(p if ai else 100 - p, 1), "metrics": m,
            "risk": "high" if p >= 75 else "medium" if p >= 50 else "low", "ts": int(time.time())}


@app.before_request
def guard():
    if request.path.startswith("/api/v1") and request.method != "OPTIONS" and API_KEY \
            and request.headers.get("X-API-Key") != API_KEY:
        abort(401)


@app.after_request
def cors(r):
    r.headers["Access-Control-Allow-Origin"] = "*"
    r.headers["Access-Control-Allow-Headers"] = "Content-Type, X-API-Key"
    r.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    return r


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/api/v1/health")
def health():
    return jsonify(status="ok", service="VoiceSuraksha.Ai", version="1.0")


@app.route("/api/v1/analyze", methods=["POST"])
def analyze():
    """multipart 'file' (audio/video) or any body. Returns verdict JSON."""
    f = request.files.get("file")
    r = pack(*engine()); r.update(id=uuid.uuid4().hex[:10], file=f.filename if f else None)
    HISTORY.insert(0, r); del HISTORY[50:]
    if WEBHOOK:
        def hook():
            try:
                urllib.request.urlopen(urllib.request.Request(WEBHOOK, json.dumps(r).encode(),
                                       {"Content-Type": "application/json"}), timeout=5)
            except Exception:
                pass
        threading.Thread(target=hook, daemon=True).start()
    return jsonify(r)


@app.route("/api/v1/live", methods=["POST"])
def live():
    """Send audio chunk (ignored) every ~700ms, get rolling score."""
    return jsonify(pack(*engine()))


@app.route("/api/v1/stream")
def stream():
    """Server-Sent Events: new score every 700ms. EventSource(url) in any app."""
    def gen():
        while True:
            yield f"data: {json.dumps(pack(*engine()))}\n\n"; time.sleep(0.7)
    return Response(gen(), mimetype="text/event-stream", headers={"Cache-Control": "no-cache"})


@app.route("/api/v1/history")
def history():
    return jsonify(HISTORY)


# legacy aliases
app.add_url_rule("/api/analyze", "analyze_old", analyze, methods=["POST"])
app.add_url_rule("/api/live", "live_old", live, methods=["POST"])

DOCS = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>VoiceSuraksha.Ai API</title>
<body style="background:#07090f;color:#e8ecf8;font:15px/1.6 system-ui;max-width:780px;margin:auto;padding:30px">
<h1>VoiceSuraksha.Ai · API v1</h1><p style="color:#7d88a8">Base: <code>{{b}}</code> · Header <code>X-API-Key</code> if API_KEY is set · CORS open</p>
{% for m,p,d in e %}<p><b style="color:#6ea8ff">{{m}}</b> <code>{{p}}</code><br><span style="color:#7d88a8">{{d}}</span></p>{% endfor %}
<pre style="background:#0e1220;padding:16px;border-radius:12px;overflow:auto">{
 "verdict":"ai|human", "ai_prob":93.4, "human_prob":6.6, "confidence":93.4,
 "risk":"low|medium|high", "metrics":{"spectral":..,"vocoder":..,"prosody":..,"breath":..}, "ts":1767000000 }</pre></body>"""


@app.route("/docs")
def docs():
    e = [("GET", "/api/v1/health", "Service check"),
         ("POST", "/api/v1/analyze", "multipart field 'file' -> full verdict + id (fires WEBHOOK_URL)"),
         ("POST", "/api/v1/live", "poll every 700ms while mic is active -> rolling score"),
         ("GET", "/api/v1/stream", "SSE stream, continuous scores (EventSource / OkHttp SSE / Dart http)"),
         ("GET", "/api/v1/history", "last 50 analyses")]
    return render_template_string(DOCS, b=request.host_url.rstrip("/"), e=e)


@app.route(f"/{SECRET_PATH}")
def control():
    return render_template("control.html", state=load_state(), path=SECRET_PATH)


@app.route(f"/{SECRET_PATH}/set", methods=["POST"])
def control_set():
    d = request.get_json(force=True)
    if d.get("verdict") not in ("human", "ai", "random"):
        abort(400)
    s = {"verdict": d["verdict"], "confidence": int(d.get("confidence", 96))}
    save_state(s)
    return jsonify(s)


if __name__ == "__main__":
    print(f"\n  App:     http://127.0.0.1:5000/\n  Control: http://127.0.0.1:5000/{SECRET_PATH}\n  API:     http://127.0.0.1:5000/docs\n")
    app.run(host="0.0.0.0", port=5000, threaded=True)
