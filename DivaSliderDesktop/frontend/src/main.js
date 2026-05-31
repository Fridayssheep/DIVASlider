import './style.css';
import {
  DefaultConfig,
  InputMethods,
  MethodRequirement,
  OpenWebInput,
  Start,
  Status,
  Stop,
} from '../wailsjs/go/main/App';

const state = {
  methods: [],
  cfg: {
    id: 'dll-tlac',
    httpAddr: ':52469',
    udpAddr: ':52468',
    httpEnabled: true,
    udpEnabled: true,
    dllInjection: true,
    joystickSlider: false,
    outputRefreshMillis: 1,
    debug: false,
  },
  status: null,
  requirement: null,
  busy: false,
  error: '',
};

const app = document.querySelector('#app');

function checked(value) {
  return value ? 'checked' : '';
}

function methodName(id) {
  return state.methods.find((item) => item.id === id)?.name || id;
}

function render() {
  const running = Boolean(state.status?.running);
  const canStart = running || !state.requirement || state.requirement.ok;
  const httpPort = portValue(state.cfg.httpAddr);
  const udpPort = portValue(state.cfg.udpAddr);
  const outputCards = state.status?.outputs?.length
    ? state.status.outputs.map(renderOutput).join('')
    : '<div class="empty">没有已启动的输出。</div>';

  app.innerHTML = `
    <main class="shell">
      <section class="topbar">
        <div>
          <p class="eyebrow">DIVA Slider</p>
          <h1>输入方式设置</h1>
        </div>
        <div class="status ${running ? 'running' : ''}">
          <span></span>${running ? '运行中' : '已停止'}
        </div>
      </section>

      <section class="grid">
        <div class="panel wide">
          <div class="panel-head">
            <h2>输入方式</h2>
            <p>${methodName(state.cfg.id)}</p>
          </div>
          <div class="method-list">
            ${state.methods.map((item) => `
              <button class="method ${state.cfg.id === item.id ? 'selected' : ''}" data-method="${item.id}" ${running ? 'disabled' : ''}>
                <strong>${item.name}</strong>
                <span>${item.description}</span>
              </button>
            `).join('')}
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>输入服务</h2>
            <p>Web 输入界面保留，手机 UDP 输入继续可用。</p>
          </div>
          <label class="field">
            <span>HTTP / Web 输入端口</span>
            <input id="httpAddr" inputmode="numeric" pattern="[0-9]*" value="${httpPort}" ${running ? 'disabled' : ''}>
          </label>
          <label class="field">
            <span>UDP 输入端口</span>
            <input id="udpAddr" inputmode="numeric" pattern="[0-9]*" value="${udpPort}" ${running ? 'disabled' : ''}>
          </label>
          <div class="toggles">
            <label><input id="httpEnabled" type="checkbox" ${checked(state.cfg.httpEnabled)} ${running ? 'disabled' : ''}> Web 输入</label>
            <label><input id="udpEnabled" type="checkbox" ${checked(state.cfg.udpEnabled)} ${running ? 'disabled' : ''}> UDP 输入</label>
            <label><input id="debug" type="checkbox" ${checked(state.cfg.debug)} ${running ? 'disabled' : ''}> 调试日志</label>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>输出细节</h2>
            <p>${methodHint()}</p>
          </div>
          ${renderRequirement()}
          ${renderMethodSettings(running)}
        </div>

        <div class="panel actions wide">
          <button id="startStop" class="primary ${running ? 'danger' : ''}" ${state.busy || !canStart ? 'disabled' : ''}>
            ${running ? '停止服务' : '启动服务'}
          </button>
          <button id="openWeb" ${!running || !state.cfg.httpEnabled ? 'disabled' : ''}>打开 Web 输入界面</button>
          <div class="url">${state.status?.httpUrl || localURL(state.cfg.httpAddr)}</div>
          ${state.error ? `<div class="error">${state.error}</div>` : ''}
        </div>

        <div class="panel wide">
          <div class="panel-head">
            <h2>运行状态</h2>
            <p>最后输入序号 ${state.status?.input?.Sequence || 0}</p>
          </div>
          <div class="outputs">${outputCards}</div>
        </div>
      </section>
    </main>
  `;

  bindEvents(running);
}

function methodHint() {
  switch (state.cfg.id) {
    case 'dll-tlac':
      return '使用现有 DLL 注入路径，适合 AFT / TLAC / PDLoader。';
    case 'joystick-slider':
      return '通过 ViGEmBus 创建 DS4 虚拟手柄，用摇杆表达滑动方向。';
    default:
      return '';
  }
}

function renderMethodSettings(running) {
  if (state.cfg.id === 'dll-tlac') {
    return `
      <div class="note">
        <div>
          <strong>共享内存</strong>
          <span>Local\\DIVASLIDER_SHARED_BUFFER</span>
        </div>
        <div class="pill">DLL 注入</div>
      </div>
    `;
  }

  return `
    <div class="note">
      <div>
        <strong>摇杆方向</strong>
        <span>需要系统已安装并运行 ViGEmBus 驱动；程序会创建一个 DS4 虚拟手柄。</span>
      </div>
      <div class="pill">ViGEmBus DS4</div>
    </div>
  `;
}

function renderRequirement() {
  if (!state.requirement) {
    return '';
  }

  const severity = state.requirement.severity || 'info';
  return `
    <div class="requirement ${severity}">
      <div>
        <strong>${state.requirement.title}</strong>
        <span>${state.requirement.message}</span>
        ${state.requirement.detail ? `<small>${state.requirement.detail}</small>` : ''}
        <em>${state.requirement.action}</em>
      </div>
      <div class="pill">${requirementLabel(state.requirement)}</div>
    </div>
  `;
}

function requirementLabel(requirement) {
  if (requirement.severity === 'ok') return '已就绪';
  if (requirement.severity === 'error') return '需要处理';
  if (requirement.severity === 'warning') return '请检查';
  return '提示';
}

function renderOutput(output) {
  const axes = output.joystickAxes;
  return `
    <div class="output">
      <div>
        <strong>${output.name}</strong>
        <span>${output.message}</span>
      </div>
      <div class="ready ${output.ready ? 'ok' : ''}">${output.ready ? '可用' : '待接入'}</div>
      ${axes ? `<code>LX ${axes.leftX} · LY ${axes.leftY} · RX ${axes.rightX} · RY ${axes.rightY}</code>` : ''}
    </div>
  `;
}

function bindEvents(running) {
  document.querySelectorAll('[data-method]').forEach((button) => {
    button.addEventListener('click', async () => {
      if (running) return;
      await selectMethod(button.dataset.method);
    });
  });

  ['httpAddr', 'udpAddr'].forEach((id) => {
    const input = document.getElementById(id);
    input?.addEventListener('input', () => {
      state.cfg[id] = listenAddr(input.value);
    });
  });

  ['httpEnabled', 'udpEnabled', 'debug'].forEach((id) => {
    const input = document.getElementById(id);
    input?.addEventListener('change', () => {
      state.cfg[id] = input.checked;
    });
  });

  document.getElementById('startStop')?.addEventListener('click', async () => {
    if (running) {
      await stopServer();
    } else {
      await startServer();
    }
  });
  document.getElementById('openWeb')?.addEventListener('click', () => OpenWebInput());
}

async function selectMethod(id) {
  state.cfg = await DefaultConfig(id);
  await refreshRequirement();
  state.error = '';
  render();
}

async function startServer() {
  state.cfg = normalizedPortConfig(state.cfg);
  state.busy = true;
  state.error = '';
  render();
  try {
    await refreshRequirement();
    state.status = await Start(state.cfg);
  } catch (error) {
    state.error = String(error);
  } finally {
    state.busy = false;
    render();
  }
}

async function stopServer() {
  state.busy = true;
  state.error = '';
  render();
  try {
    state.status = await Stop();
  } catch (error) {
    state.error = String(error);
  } finally {
    state.busy = false;
    if (state.status) {
      state.cfg = state.status.config || state.cfg;
    }
    render();
  }
}

function localURL(addr) {
  if (!addr) return '';
  return addr.startsWith(':') ? `http://127.0.0.1${addr}/` : `http://${addr}/`;
}

function portValue(addr) {
  if (!addr) return '';
  if (addr.startsWith(':')) return addr.slice(1);
  const match = addr.match(/:(\d+)$/);
  return match ? match[1] : addr;
}

function listenAddr(value) {
  const port = String(value || '').trim().replace(/[^0-9]/g, '');
  return port ? `:${port}` : '';
}

function normalizedPortConfig(cfg) {
  return {
    ...cfg,
    httpAddr: listenAddr(portValue(cfg.httpAddr)),
    udpAddr: listenAddr(portValue(cfg.udpAddr)),
  };
}

async function refreshStatus() {
  if (state.busy) {
    return;
  }
  try {
    state.status = await Status();
    if (!state.requirement || state.requirement.methodId !== state.cfg.id) {
      await refreshRequirement();
    }
    render();
  } catch (error) {
    state.error = String(error);
    render();
  }
}

async function refreshRequirement() {
  state.requirement = await MethodRequirement(state.cfg.id);
}

async function boot() {
  state.methods = await InputMethods();
  state.status = await Status();
  state.cfg = state.status.config || state.cfg;
  await refreshRequirement();
  render();
  setInterval(refreshStatus, 1000);
}

boot();
