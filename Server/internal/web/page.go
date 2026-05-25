package web

var indexHTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, viewport-fit=cover">
<title>DIVA Slider</title>
<style>
:root {
  --ink:#050a12;
  --panel:#0f172a;
  --stroke:#2b4154;
  --text:#ecfeff;
  --muted:#94a3b8;
  --accent:#5eead4;
  --hot:#f59e0b;
  --blue:#38bdf8;
  --green:#86f6cf;
  --pink:#e066ff;
  --cross:#508bff;
  --red:#ff7381;
}
* { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
html, body { margin:0; width:100%; height:100%; overflow:hidden; overscroll-behavior:none; background:var(--ink); color:var(--text); touch-action:none; user-select:none; }
body { position:fixed; inset:0; }
main { position:relative; width:100%; height:100%; background:linear-gradient(135deg, #050a12 0%, #0d232a 100%); }
main::before { content:""; position:absolute; inset:0; background:linear-gradient(115deg, transparent 0 28%, rgba(220,252,248,.08) 28.2% 28.7%, transparent 29% 100%); background-size:48px 100%; opacity:.55; pointer-events:none; }
#slider { position:absolute; inset:0; display:grid; grid-template-columns:repeat(32, 1fr); background:#111827; overflow:hidden; }
.cell { position:relative; border-left:1px solid #263043; background:#111827; --led-alpha:0; --led-color:56,189,248; }
.cell.edge { background:#1e293b; }
.cell::after { content:""; position:absolute; inset:0; background:rgba(var(--led-color), var(--led-alpha)); box-shadow:inset 0 0 32px rgba(var(--led-color), var(--led-alpha)); pointer-events:none; }
.cell.on { background:#173244; box-shadow:inset 0 -12px 0 rgba(255,238,170,.68), inset 0 0 22px rgba(255,255,255,.22); }
.edge-label { position:absolute; top:50%; z-index:2; transform:translate(-50%, -50%); color:rgba(226,232,240,.72); font-weight:900; font-size:clamp(34px, 8vw, 64px); letter-spacing:0; pointer-events:none; text-shadow:0 2px 18px rgba(0,0,0,.45); }
.edge-label.left { left:6.25%; }
.edge-label.right { left:93.75%; }
.status-chip { position:absolute; left:14px; top:10px; z-index:5; min-height:42px; max-width:240px; border:1px solid var(--stroke); border-radius:18px; background:rgba(15,23,42,.9); color:var(--text); display:flex; align-items:center; gap:8px; padding:0 16px; font-size:14px; font-weight:800; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; pointer-events:none; }
.status-dot { width:8px; height:8px; border-radius:999px; background:#ef4444; box-shadow:0 0 8px rgba(239,68,68,.65); flex:0 0 auto; }
.status-chip.connecting .status-dot { background:var(--hot); box-shadow:0 0 10px rgba(245,158,11,.85); animation:status-pulse 900ms ease-in-out infinite alternate; }
.status-chip.connected { background:#115e59; }
.status-chip.connected .status-dot { background:var(--accent); box-shadow:0 0 12px rgba(94,234,212,.9); }
@keyframes status-pulse { from { opacity:.35; } to { opacity:1; } }
.input-toggle { position:absolute; right:14px; top:10px; z-index:6; min-height:42px; border:1px solid var(--stroke); border-radius:18px; background:rgba(15,23,42,.9); color:var(--text); padding:0 16px; font-size:13px; font-weight:900; white-space:nowrap; }
.input-toggle.enabled { background:rgba(94,234,212,.92); color:#041018; border-color:transparent; }
.tap-fill { position:relative; overflow:hidden; }
.input-toggle.tap-fill { position:absolute; }
.tap-fill::after { content:""; position:absolute; left:var(--tap-x, 50%); top:var(--tap-y, 50%); width:1px; height:1px; border-radius:999px; background:var(--tap-color, rgba(94,234,212,.42)); transform:translate(-50%, -50%) scale(0); opacity:0; pointer-events:none; }
.tap-fill.flash::after { animation:tap-fill 230ms ease-out; }
@keyframes tap-fill { 0% { transform:translate(-50%, -50%) scale(0); opacity:.95; } 100% { transform:translate(-50%, -50%) scale(260); opacity:0; } }
#buttonPanel { position:absolute; left:18px; right:18px; bottom:10px; min-height:34px; height:34px; border-radius:16px; background:rgba(12,18,30,.78); z-index:4; overflow:visible; transition:height 250ms ease, background 250ms ease; }
#buttonPanel.expanded { height:min(38vh, 320px); background:rgba(12,18,30,.9); }
.panel-tool { position:absolute; top:-4px; height:30px; border:0; border-radius:14px; background:rgba(220,252,248,.92); color:#08131f; font-size:12px; font-weight:900; display:flex; align-items:center; justify-content:center; z-index:7; }
#lockButton { left:10px; width:56px; }
#handleButton { left:50%; width:142px; transform:translateX(-50%); }
#buttonPanel.locked #handleButton { display:none; }
#buttonGrid { position:absolute; inset:22px 10px 10px 10px; display:grid; grid-template-columns:1fr 1fr 1fr 1fr minmax(58px, .72fr); gap:18px; align-items:center; opacity:0; transform:translateY(10px); transition:opacity 180ms ease, transform 180ms ease; pointer-events:none; }
#buttonPanel.expanded #buttonGrid { opacity:1; transform:translateY(0); pointer-events:auto; }
.game-button { width:min(100%, 220px); height:min(100%, 220px); border:0; background:transparent; color:var(--text); position:relative; min-width:0; min-height:0; padding:0; touch-action:none; justify-self:center; align-self:center; }
.game-button .shape { position:absolute; left:50%; top:50%; width:92%; height:92%; transform:translate(-50%, -50%); overflow:visible; filter:drop-shadow(0 0 10px var(--btn-color)); opacity:.72; }
.game-button.down .shape, .game-button.lit .shape { filter:drop-shadow(0 0 18px var(--btn-color)); opacity:1; }
.game-button .shape * { fill:none; stroke:var(--btn-color); stroke-linecap:round; stroke-linejoin:round; vector-effect:non-scaling-stroke; }
.game-button .shape .outer { stroke-width:6; opacity:.5; }
.game-button .shape .inner { stroke-width:8; opacity:.58; }
.game-button.down .shape .outer, .game-button.lit .shape .outer { opacity:.85; }
.game-button.down .shape .inner, .game-button.lit .shape .inner { opacity:1; }
.side-buttons { display:grid; grid-template-rows:repeat(4, 1fr); gap:8px; min-height:0; height:100%; justify-items:center; align-items:center; grid-column:5; grid-row:1; }
.side-button { width:min(100%, 72px); height:min(100%, 72px); border:3px solid rgba(148,163,184,.42); border-radius:999px; background:rgba(15,23,42,.54); color:rgba(236,254,255,.7); min-height:0; font-size:10px; font-weight:700; touch-action:none; padding:0; }
.side-button.coin { border-color:rgba(241,198,75,.5); color:rgba(241,198,75,.74); }
.side-button.service { border-color:rgba(94,234,212,.45); color:rgba(236,254,255,.78); }
.side-button.down { color:#fff; border-color:rgba(255,255,255,.65); box-shadow:inset 0 0 0 999px rgba(255,255,255,.1), 0 0 18px rgba(255,255,255,.18); }
.side-button.coin.down { border-color:rgba(241,198,75,.9); box-shadow:0 0 18px rgba(241,198,75,.35); }
button { cursor:pointer; letter-spacing:0; }
@media (max-width: 720px) {
  .status-chip { left:10px; top:10px; padding:0 12px; }
  .input-toggle { right:10px; top:10px; padding:0 12px; }
  #buttonPanel { left:10px; right:10px; }
  #buttonPanel.expanded { height:min(40vh, 260px); }
  #buttonGrid { grid-template-columns:1fr 1fr 1fr 1fr 58px; gap:10px; }
}
</style>
</head>
<body>
<main>
  <section id="slider">
    <div class="edge-label left">L</div>
    <div class="edge-label right">R</div>
  </section>
  <div id="status" class="status-chip">
    <span class="status-dot"></span>
    <span id="statusText">connecting</span>
  </div>
  <button id="inputToggle" class="input-toggle tap-fill">INPUT OFF</button>
  <section id="buttonPanel">
    <button id="lockButton" class="panel-tool tap-fill">LOCK</button>
    <button id="handleButton" class="panel-tool tap-fill">SHOW BUTTONS</button>
    <div id="buttonGrid"></div>
  </section>
</main>
<script>
const BUTTONS = {
  circle: 1,
  cross: 2,
  square: 4,
  triangle: 8,
  start: 16,
  test: 32,
  service: 64
};
const gameDefs = [
  { id:"triangle", bit:BUTTONS.triangle, cls:"tri", color:"#86f6cf" },
  { id:"square", bit:BUTTONS.square, cls:"sqr", color:"#e066ff" },
  { id:"cross", bit:BUTTONS.cross, cls:"cross", color:"#508bff" },
  { id:"circle", bit:BUTTONS.circle, cls:"circle", color:"#ff7381" }
];

const slider = document.getElementById("slider");
const buttonPanel = document.getElementById("buttonPanel");
const buttonGrid = document.getElementById("buttonGrid");
const statusEl = document.getElementById("status");
const statusText = document.getElementById("statusText");
const inputToggle = document.getElementById("inputToggle");
const handleButton = document.getElementById("handleButton");
const lockButton = document.getElementById("lockButton");
const cells = [];
const buttonElements = new Map();
let ws;
let buttons = 0;
let pressure = new Uint8Array(32);
let activePointers = new Map();
let panelExpanded = false;
let panelLocked = false;
let inputEnabled = false;

for (let i = 0; i < 32; i++) {
  const cell = document.createElement("div");
  cell.className = "cell" + (i < 4 || i >= 28 ? " edge" : "");
  slider.appendChild(cell);
  cells.push(cell);
}

for (const def of gameDefs) {
  const button = document.createElement("button");
  button.className = "game-button " + def.cls;
  button.style.setProperty("--btn-color", def.color);
  button.innerHTML = shapeSVG(def.cls);
  buttonGrid.appendChild(button);
  buttonElements.set(def.bit, button);
  wireHoldButton(button, def.bit);
}

const side = document.createElement("div");
side.className = "side-buttons";
buttonGrid.appendChild(side);

const testButton = makeSideButton("TEST", "service");
side.appendChild(testButton);
buttonElements.set(BUTTONS.test, testButton);
wirePulseButton(testButton, BUTTONS.test);

const serviceButton = makeSideButton("SERVICE", "service");
side.appendChild(serviceButton);
buttonElements.set(BUTTONS.service, serviceButton);
wirePulseButton(serviceButton, BUTTONS.service);

const startButton = document.createElement("button");
startButton.className = "side-button tap-fill";
startButton.textContent = "START";
side.appendChild(startButton);
buttonElements.set(BUTTONS.start, startButton);
wireHoldButton(startButton, BUTTONS.start);

const coinButton = document.createElement("button");
coinButton.className = "side-button coin tap-fill";
coinButton.textContent = "COIN";
side.appendChild(coinButton);
wireCoinButton(coinButton);

function makeSideButton(label, extraClass) {
  const button = document.createElement("button");
  button.className = "side-button " + extraClass + " tap-fill";
  button.textContent = label;
  return button;
}

function shapeSVG(cls) {
  if (cls === "tri") {
    return '<svg class="shape" viewBox="0 0 100 100" aria-hidden="true"><circle class="outer" cx="50" cy="50" r="44"></circle><path class="inner" d="M50 28 L69 66 L31 66 Z"></path></svg>';
  }
  if (cls === "sqr") {
    return '<svg class="shape" viewBox="0 0 100 100" aria-hidden="true"><circle class="outer" cx="50" cy="50" r="44"></circle><rect class="inner" x="35" y="35" width="30" height="30"></rect></svg>';
  }
  if (cls === "cross") {
    return '<svg class="shape" viewBox="0 0 100 100" aria-hidden="true"><circle class="outer" cx="50" cy="50" r="44"></circle><path class="inner" d="M36 36 L64 64 M64 36 L36 64"></path></svg>';
  }
  return '<svg class="shape" viewBox="0 0 100 100" aria-hidden="true"><circle class="outer" cx="50" cy="50" r="44"></circle><circle class="inner" cx="50" cy="50" r="20"></circle></svg>';
}

function connect() {
  closeSocket();
  setStatus("connecting", "connecting");
  const scheme = location.protocol === "https:" ? "wss://" : "ws://";
  ws = new WebSocket(scheme + location.host + "/ws");
  ws.onopen = () => {
    setStatus("connected", "connected");
    send();
  };
  ws.onclose = () => {
    setStatus("disconnected", "");
    setTimeout(connect, 800);
  };
  ws.onerror = () => {
    setStatus("disconnected", "");
  };
}

function closeSocket() {
  if (!ws) return;
  const old = ws;
  ws = undefined;
  old.onclose = null;
  old.onerror = null;
  old.close();
}

function setStatus(text, state) {
  statusText.textContent = text;
  statusEl.classList.remove("connecting", "connected");
  if (state) statusEl.classList.add(state);
}

handleButton.addEventListener("click", e => {
  flashTap(handleButton, e);
  if (panelLocked) return;
  panelExpanded = !panelExpanded;
  updatePanel();
});

lockButton.addEventListener("click", e => {
  flashTap(lockButton, e);
  panelLocked = !panelLocked;
  if (panelLocked) panelExpanded = true;
  updatePanel();
});

inputToggle.addEventListener("click", e => {
  flashTap(inputToggle, e);
  inputEnabled = !inputEnabled;
  if (!inputEnabled) {
    clearLocalInput();
  }
  updateInputToggle();
  send();
});

function updatePanel() {
  buttonPanel.classList.toggle("expanded", panelExpanded);
  buttonPanel.classList.toggle("locked", panelLocked);
  lockButton.textContent = panelLocked ? "UNLOCK" : "LOCK";
  handleButton.textContent = panelExpanded ? "HIDE BUTTONS" : "SHOW BUTTONS";
}

function updateInputToggle() {
  inputToggle.classList.toggle("enabled", inputEnabled);
  inputToggle.textContent = inputEnabled ? "INPUT ON" : "INPUT OFF";
}

function clearLocalInput() {
  buttons = 0;
  activePointers.clear();
  pressure.fill(0);
  for (const cell of cells) cell.classList.remove("on");
  for (const button of buttonElements.values()) button.classList.remove("down");
}

function wireHoldButton(button, bit) {
  button.addEventListener("pointerdown", e => {
    e.preventDefault();
    if (!inputEnabled) return;
    button.setPointerCapture(e.pointerId);
    flashTap(button, e);
    buttons |= bit;
    button.classList.add("down");
    send();
  });
  const up = () => {
    buttons &= ~bit;
    button.classList.remove("down");
    send();
  };
  button.addEventListener("pointerup", up);
  button.addEventListener("pointercancel", up);
}

function wirePulseButton(button, bit) {
  button.addEventListener("pointerdown", e => {
    e.preventDefault();
    if (!inputEnabled) return;
    flashTap(button, e);
    pulseButton(button, bit);
  });
}

function pulseButton(button, bit) {
  buttons |= bit;
  button.classList.add("down");
  send();
  setTimeout(() => {
    buttons &= ~bit;
    button.classList.remove("down");
    send();
  }, 70);
}

function wireCoinButton(button) {
  button.addEventListener("pointerdown", e => {
    e.preventDefault();
    if (!inputEnabled) return;
    flashTap(button, e);
    button.classList.add("down");
    send(true);
  });
  const up = () => button.classList.remove("down");
  button.addEventListener("pointerup", up);
  button.addEventListener("pointercancel", up);
}

function flashTap(el, event) {
  const rect = el.getBoundingClientRect();
  el.style.setProperty("--tap-x", (event.clientX - rect.left) + "px");
  el.style.setProperty("--tap-y", (event.clientY - rect.top) + "px");
  el.classList.remove("flash");
  void el.offsetWidth;
  el.classList.add("flash");
}

function pointerToCell(event) {
  const rect = slider.getBoundingClientRect();
  const x = Math.min(Math.max(event.clientX - rect.left, 0), rect.width - 1);
  return Math.max(0, Math.min(31, Math.floor(x / rect.width * 32)));
}

function rebuildPressure() {
  pressure.fill(0);
  for (const idx of activePointers.values()) pressure[idx] = 0x80;
  for (let i = 0; i < 32; i++) cells[i].classList.toggle("on", pressure[i] !== 0);
}

function renderLEDs(rgb) {
  if (!Array.isArray(rgb) || rgb.length < 96) return;
  for (let i = 0; i < 32; i++) {
    const r = clampByte(rgb[i * 3] || 0);
    const g = clampByte(rgb[i * 3 + 1] || 0);
    const b = clampByte(rgb[i * 3 + 2] || 0);
    const intensity = Math.max(r, g, b) / 255;
    cells[i].style.setProperty("--led-color", r + "," + g + "," + b);
    cells[i].style.setProperty("--led-alpha", (intensity * 0.55).toFixed(3));
  }
}

function clampByte(value) {
  return Math.max(0, Math.min(255, Number(value) || 0));
}

function renderButtonLEDs(values) {
  if (!Array.isArray(values)) return;
  const ledMap = [
    [BUTTONS.triangle, 6],
    [BUTTONS.square, 7],
    [BUTTONS.cross, 8],
    [BUTTONS.circle, 9]
  ];
  for (const [bit, ledIndex] of ledMap) {
    const button = buttonElements.get(bit);
    if (!button) continue;
    button.classList.toggle("lit", (values[ledIndex] || 0) > 0);
  }
}

async function pollStatus() {
  try {
    const response = await fetch("/status", { cache:"no-store" });
    if (!response.ok) return;
    const status = await response.json();
    if (status.leds) {
      renderLEDs(status.leds.slider);
      renderButtonLEDs(status.leds.buttons);
    }
  } catch (err) {
  }
}

slider.addEventListener("pointerdown", e => {
  if (e.target !== slider && !e.target.classList.contains("cell")) return;
  e.preventDefault();
  if (!inputEnabled) return;
  slider.setPointerCapture(e.pointerId);
  activePointers.set(e.pointerId, pointerToCell(e));
  rebuildPressure();
  send();
});

slider.addEventListener("pointermove", e => {
  if (!activePointers.has(e.pointerId)) return;
  e.preventDefault();
  activePointers.set(e.pointerId, pointerToCell(e));
  rebuildPressure();
  send();
});

function releasePointer(e) {
  activePointers.delete(e.pointerId);
  rebuildPressure();
  send();
}

slider.addEventListener("pointerup", releasePointer);
slider.addEventListener("pointercancel", releasePointer);

function send(coinPulse = false) {
  if (!inputEnabled) return;
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify({ buttons, slider:Array.from(pressure), coin:coinPulse }));
}

updatePanel();
updateInputToggle();
setInterval(() => send(), 50);
setInterval(pollStatus, 80);
connect();
pollStatus();
</script>
</body>
</html>`
