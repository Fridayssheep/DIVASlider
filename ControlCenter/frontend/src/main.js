import './style.css';
import { loadIcons } from 'iconify-icon';
import {
  DefaultConfig,
  InputMethods,
  MethodRequirement,
  OpenWebInput,
  Start,
  Status,
  Stop,
  GetLogs,
} from '../wailsjs/go/main/App';

const iconsToPreload = [
  'mdi:alert-circle',
  'mdi:close',
  'mdi:close-circle',
  'mdi:alert',
  'mdi:information',
  'mdi:check-circle',
  'mdi:clock-outline',
];

loadIcons(iconsToPreload);

function disableWebViewZoom() {
  const zoomKeys = new Set(['+', '-', '=', '_', '0']);
  const zoomCodes = new Set([
    'Equal',
    'Minus',
    'Digit0',
    'NumpadAdd',
    'NumpadSubtract',
    'Numpad0',
  ]);

  const stopZoom = (event) => {
    event.preventDefault();
    event.stopPropagation();
  };

  window.addEventListener('wheel', (event) => {
    if (event.ctrlKey) {
      stopZoom(event);
    }
  }, { passive: false, capture: true });

  window.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && (zoomKeys.has(event.key) || zoomCodes.has(event.code))) {
      stopZoom(event);
    }
  }, { capture: true });

  ['gesturestart', 'gesturechange', 'gestureend'].forEach((type) => {
    window.addEventListener(type, stopZoom, { passive: false, capture: true });
  });
}

disableWebViewZoom();

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
  busyAction: '', // 'starting' | 'stopping' | ''
  showDebugLog: false,
  debugLogs: [],
  lastErrorMessage: '', // 用于防止错误消息重复触发动画
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
  const canStart = !running && (!state.requirement || state.requirement.ok);
  const httpPort = portValue(state.cfg.httpAddr);
  const udpPort = portValue(state.cfg.udpAddr);
  const outputCards = state.status?.outputs?.length
    ? state.status.outputs.map(renderOutput).join('')
    : '<div class="empty">没有已启动的输出。</div>';

  app.innerHTML = `
    <main class="shell">
      <section class="topbar">
      </section>

      ${state.error ? `
        <section class="error-banner ${state.error !== state.lastErrorMessage ? 'error-banner-animate' : ''}">
          <div class="error-icon">
            <iconify-icon icon="mdi:alert-circle" width="20"></iconify-icon>
          </div>
          <div class="error-content">
            <strong>操作失败</strong>
            <span>${state.error}</span>
          </div>
          <button class="error-close" id="closeError">
            <iconify-icon icon="mdi:close" width="18"></iconify-icon>
          </button>
        </section>
      ` : ''}

      <section class="grid">
        <div class="panel wide">
          <div class="panel-head">
            <h2>输入方式</h2>
          </div>
          <div class="method-list">
            ${state.methods.map((item) => {
              const desc = getMethodDescription(item.id);
              return `
                <button class="method ${state.cfg.id === item.id ? 'selected' : ''}" data-method="${item.id}" ${running || state.busy ? 'disabled' : ''}>
                  <strong>${item.name}</strong>
                  <span>${desc}</span>
                </button>
              `;
            }).join('')}
          </div>
        </div>

        <div class="panel wide">
          <div class="panel-head">
            <h2>输入服务</h2>
          </div>

          ${renderRequirement()}

          <div class="service-grid">
            <label class="field">
              <span>HTTP / Web 端口</span>
              <input id="httpAddr" type="number" min="1" max="65535" value="${httpPort}" ${running || state.busy ? 'disabled' : ''}>
            </label>
            <label class="field">
              <span>UDP 输入端口</span>
              <input id="udpAddr" type="number" min="1" max="65535" value="${udpPort}" ${running || state.busy ? 'disabled' : ''}>
            </label>
          </div>

          <div class="toggles">
            <label><input id="httpEnabled" type="checkbox" ${checked(state.cfg.httpEnabled)} ${running || state.busy ? 'disabled' : ''}> 启用 Web 输入</label>
            <label><input id="udpEnabled" type="checkbox" ${checked(state.cfg.udpEnabled)} ${running || state.busy ? 'disabled' : ''}> 启用 UDP 输入</label>
            <label><input id="debug" type="checkbox" ${checked(state.cfg.debug)} ${running || state.busy ? 'disabled' : ''}> 调试日志</label>
          </div>

          <div class="service-actions">
            <button id="startStop" class="btn-primary ${running ? 'btn-danger' : ''}" ${state.busy ? 'disabled' : ''}>
              ${state.busy ? (state.busyAction === 'starting' ? '启动中...' : '停止中...') : (running ? '停止服务' : '启动服务')}
            </button>
            <button id="openWeb" class="btn-secondary" ${!running || !state.cfg.httpEnabled || state.busy ? 'disabled' : ''}>
              打开 Web 输入界面
            </button>
          </div>

          ${running && state.status?.httpUrl ? `
            <div class="service-url">
              <span>Web 地址:</span>
              <code>${state.status.httpUrl}</code>
            </div>
          ` : ''}
        </div>

        ${state.cfg.debug ? `
          <div class="panel wide">
            <div class="panel-head">
              <h2>运行状态</h2>
              <p>输入序号: ${state.status?.input?.Sequence || 0}${renderLastInputTime()}</p>
            </div>
            ${running ? `
              <div class="debug-log-container">
                ${state.debugLogs.length > 0 ? state.debugLogs.slice(-50).map(log => `<div class="debug-line">${escapeHtml(log)}</div>`).join('') : '<div class="debug-empty">等待日志输出...</div>'}
              </div>
            ` : '<div class="debug-empty">服务未运行</div>'}
          </div>
        ` : ''}
      </section>
    </main>
  `;

  bindEvents(running);
}

function getRenderSnapshot() {
  return JSON.stringify({
    methods: state.methods,
    cfg: state.cfg,
    status: {
      running: Boolean(state.status?.running),
      httpUrl: state.status?.httpUrl || '',
      outputs: state.status?.outputs || [],
      input: state.cfg.debug ? state.status?.input || null : null,
    },
    requirement: state.requirement,
    busy: state.busy,
    error: state.error,
    busyAction: state.busyAction,
    debugLogs: state.cfg.debug ? state.debugLogs.slice(-50) : [],
    lastErrorMessage: state.lastErrorMessage,
  });
}

function getMethodDescription(id) {
  switch (id) {
    case 'dll-tlac':
      return '对 Project DIVA Arcade 系列进行 hook，需要 Segatools 或 PDLoader';
    case 'joystick-slider':
      return '兼容其他 Project DIVA 的模拟手柄操作';
    default:
      return '';
  }
}

function renderLastInputTime() {
  if (!state.status?.input?.UpdatedMillis) return '';
  const now = Date.now();
  const diff = now - state.status.input.UpdatedMillis;
  if (diff < 2000) return ' · <span style="color: #58c889">刚刚活动</span>';
  if (diff < 10000) return ` · ${Math.floor(diff / 1000)}秒前`;
  return '';
}

function renderRequirement() {
  if (!state.requirement) {
    return '';
  }

  const severity = state.requirement.severity || 'info';

  // 只在有问题时显示
  if (severity === 'ok') {
    return '';
  }

  const iconMap = {
    error: 'mdi:close-circle',
    warning: 'mdi:alert',
    info: 'mdi:information'
  };
  const icon = iconMap[severity] || 'mdi:information';

  return `
    <div class="requirement ${severity}">
      <div class="requirement-icon">
        <iconify-icon icon="${icon}" width="24"></iconify-icon>
      </div>
      <div class="requirement-content">
        <strong>${state.requirement.title}</strong>
        <span>${state.requirement.message}</span>
        ${state.requirement.detail ? `<p class="requirement-detail">${state.requirement.detail}</p>` : ''}
        ${state.requirement.action ? `<p class="requirement-action">${state.requirement.action}</p>` : ''}
      </div>
    </div>
  `;
}

function renderOutput(output) {
  return `
    <div class="output">
      <div class="output-info">
        <strong>${output.name}</strong>
        <span>${output.message}</span>
      </div>
      <div class="output-status">
        <div class="ready ${output.ready ? 'ok' : ''}">
          ${output.ready
            ? '<iconify-icon icon="mdi:check-circle" width="16"></iconify-icon> 可用'
            : '<iconify-icon icon="mdi:clock-outline" width="16"></iconify-icon> 待接入'}
        </div>
      </div>
    </div>
  `;
}

function bindEvents(running) {
  document.querySelectorAll('[data-method]').forEach((button) => {
    button.addEventListener('click', async () => {
      if (running || state.busy) return;
      await selectMethod(button.dataset.method);
    });
  });

  ['httpAddr', 'udpAddr'].forEach((id) => {
    const input = document.getElementById(id);
    input?.addEventListener('input', () => {
      const port = parseInt(input.value, 10);
      if (port >= 1 && port <= 65535) {
        state.cfg[id] = `:${port}`;
        input.setCustomValidity('');
      } else {
        input.setCustomValidity('端口必须在 1-65535 之间');
      }
    });
  });

  const debugCheckbox = document.getElementById('debug');
  debugCheckbox?.addEventListener('change', () => {
    state.cfg.debug = debugCheckbox.checked;
    render();
  });

  ['httpEnabled', 'udpEnabled'].forEach((id) => {
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

  document.getElementById('closeError')?.addEventListener('click', () => {
    state.error = '';
    state.lastErrorMessage = '';
    render();
  });
}

async function selectMethod(id) {
  state.cfg = await DefaultConfig(id);
  await refreshRequirement();
  state.error = '';
  state.lastErrorMessage = '';
  render();
}

async function startServer() {
  state.cfg = normalizedPortConfig(state.cfg);
  state.busy = true;
  state.busyAction = 'starting';
  state.error = '';
  state.lastErrorMessage = '';
  render();

  try {
    await refreshRequirement();
    if (state.requirement && state.requirement.severity === 'error') {
      throw new Error(state.requirement.title + ': ' + state.requirement.action);
    }
    state.status = await Start(state.cfg);
    state.cfg = state.status.config || state.cfg;

    // 如果启用了调试日志，清空旧日志
    if (state.cfg.debug) {
      state.debugLogs = [];
    }
  } catch (error) {
    const errorMsg = String(error);
    state.error = errorMsg;
    state.lastErrorMessage = errorMsg;
  } finally {
    state.busy = false;
    state.busyAction = '';
    render();
  }
}

async function stopServer() {
  state.busy = true;
  state.busyAction = 'stopping';
  state.error = '';
  render();

  try {
    state.status = await Stop();
    state.debugLogs = [];
  } catch (error) {
    state.error = String(error);
  } finally {
    state.busy = false;
    state.busyAction = '';
    if (state.status) {
      state.cfg = state.status.config || state.cfg;
    }
    render();
  }
}

function addDebugLog(message) {
  const timestamp = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  state.debugLogs.push(`[${timestamp}] ${message}`);
  if (state.debugLogs.length > 200) {
    state.debugLogs = state.debugLogs.slice(-100);
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
    const prevStatus = state.status;
    const prevSnapshot = getRenderSnapshot();
    state.status = await Status();

    // 获取真实的日志
    if (state.cfg.debug && state.status?.running) {
      try {
        const newLogs = await GetLogs();
        if (newLogs && newLogs.length > 0) {
          state.debugLogs.push(...newLogs);
          // 只保留最近200条
          if (state.debugLogs.length > 200) {
            state.debugLogs = state.debugLogs.slice(-200);
          }
        }
      } catch (err) {
        console.error('Failed to get logs:', err);
      }
    }

    if (!state.requirement || state.requirement.methodId !== state.cfg.id) {
      await refreshRequirement();
    }

    if (getRenderSnapshot() !== prevSnapshot) {
      render();
    }
  } catch (error) {
    // 静默失败，避免频繁显示错误
    console.error('Status refresh failed:', error);
  }
}

async function refreshRequirement() {
  state.requirement = await MethodRequirement(state.cfg.id);
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
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
