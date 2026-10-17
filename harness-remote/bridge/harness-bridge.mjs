#!/usr/bin/env node
// Harness Bridge — runs on the computer that hosts your agent harnesses and local models.
// Exposes a small authenticated WebSocket API that the Harness Remote Android app talks to.
// Zero dependencies: Node.js >= 18 standard library only.

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const VERSION = '0.8.0';
const PROTOCOL = 1;
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE = 24 * 1024 * 1024;
const MAX_IMAGES = 6;
const EFFORTS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
const MAX_PROMPT = 100 * 1024;
const MAX_EVENTS = 4000;
const FLUSH_MS = 80;
const WAITING_MS = 1500;
const IS_WIN = process.platform === 'win32';
const WAITING_RE = /(\?|\[y\/n\]|\(y\/n\)|\(yes\/no\)|:|>|›|❯|\$|#)\s*$/i;

if (Number(process.versions.node.split('.')[0]) < 18) {
  console.error(`Harness Bridge needs Node.js 18 or newer. You have ${process.version}. Download it from https://nodejs.org`);
  process.exit(1);
}

// ---------------------------------------------------------------- arguments

const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i >= 0 ? (argv[i + 1] ?? true) : undefined;
};
if (argv.includes('--help') || argv.includes('-h')) {
  console.log(`Harness Bridge ${VERSION}

Usage: node harness-bridge.mjs [options]

  --config <file>     Config file (default ~/.harness-remote/config.json)
  --host <addr>       Bind address (default from config, else 0.0.0.0)
  --port <n>          Port (default from config, else 8787)
  --rotate-token      Generate a new access token (unpairs every phone)
  --print-config      Print the effective harness list and exit
`);
  process.exit(0);
}

const dataDir = path.join(os.homedir(), '.harness-remote');
const configPath = path.resolve(flag('--config') || path.join(dataDir, 'config.json'));
const sessionsPath = path.join(path.dirname(configPath), 'sessions.json');
fs.mkdirSync(path.dirname(configPath), { recursive: true });

// ---------------------------------------------------------------- config

function loadJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

function saveJson(file, value, mode) {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2), { mode });
  fs.renameSync(tmp, file);
}

const config = loadJson(configPath, {});
let configDirty = !fs.existsSync(configPath);
if (!config.token || argv.includes('--rotate-token')) {
  config.token = crypto.randomBytes(24).toString('base64url');
  configDirty = true;
}
if (config.port == null) config.port = 8787;
if (config.host == null) config.host = '0.0.0.0';
if (config.roots == null) config.roots = [os.homedir()];
if (config.ollama == null) config.ollama = process.env.OLLAMA_HOST || 'http://127.0.0.1:11434';
if (config.lmstudio == null) config.lmstudio = 'http://127.0.0.1:1234';
if (config.harnesses == null) config.harnesses = [];
if (configDirty) saveJson(configPath, config, 0o600);
config.ollama = localUrl(config.ollama, 11434);
config.lmstudio = localUrl(config.lmstudio, 1234);

const port = Number(flag('--port') || config.port);
const host = String(flag('--host') || config.host);

function localUrl(value, defaultPort) {
  try {
    const u = new URL(/^https?:\/\//.test(value) ? value : `http://${value}`);
    if (u.hostname === '0.0.0.0' || u.hostname === '[::]') u.hostname = '127.0.0.1';
    if (!u.port && u.protocol === 'http:' && !/:\d+/.test(value)) u.port = String(defaultPort);
    return u.origin;
  } catch {
    return `http://127.0.0.1:${defaultPort}`;
  }
}

// ---------------------------------------------------------------- harness presets

// Placeholders: {prompt} {model} {session} {cwd} {ollama} {lmstudio}
// "{modelArgs}" as a whole arg is replaced by modelArgs when a model is chosen, removed otherwise.
// mode "turn": one process per message, stdin closed unless stdin: 'pipe'. mode "interactive": one long-lived process, messages go to stdin.
// backends: per model server overrides (modelArgs, env, modelEnv, args, continueArgs). The first one is the default.
const LMSTUDIO = 'lmstudio';
const OLLAMA = 'ollama';
const PROVIDERS = [LMSTUDIO, OLLAMA];

const PRESETS = [
  {
    id: 'pi',
    name: 'Pi',
    description: 'Live thinking, tool calls and permission prompts (pi RPC mode). The bridge adds the model you pick to ~/.pi/agent/models.json if it is missing.',
    mode: 'rpc',
    command: 'pi',
    args: ['--mode', 'rpc', '--session-id', 'harness-remote-{session}', '{modelArgs}', '{thinkingArgs}', '-e', '{piPermissionExtension}'],
    effort: true,
    ignoreStderr: ["^Warning: No project session found with id 'harness-remote-"],
    backends: {
      [LMSTUDIO]: { modelArgs: ['--provider', 'lmstudio', '--model', '{model}'], piProvider: { key: 'lmstudio', baseUrl: '{lmstudio}/v1', apiKey: 'lm-studio' } },
      [OLLAMA]: { modelArgs: ['--provider', 'ollama', '--model', '{model}'], piProvider: { key: 'ollama', baseUrl: '{ollama}/v1', apiKey: 'ollama' } },
    },
    requiresModel: true,
  },
  {
    id: 'aider',
    name: 'Aider',
    description: 'Aider pair programmer, one message per run, chat history restored between runs.',
    mode: 'turn',
    command: 'aider',
    args: ['{modelArgs}', '--yes-always', '--no-pretty', '--no-fancy-input', '--no-check-update', '--no-show-release-notes', '--no-show-model-warnings', '--no-suggest-shell-commands', '--message', '{prompt}'],
    continueArgs: ['{modelArgs}', '--yes-always', '--no-pretty', '--no-fancy-input', '--no-check-update', '--no-show-release-notes', '--no-show-model-warnings', '--no-suggest-shell-commands', '--restore-chat-history', '--message', '{prompt}'],
    backends: {
      [LMSTUDIO]: { modelArgs: ['--model', 'lm_studio/{model}'], env: { LM_STUDIO_API_BASE: '{lmstudio}/v1', LM_STUDIO_API_KEY: 'dummy-api-key' } },
      [OLLAMA]: { modelArgs: ['--model', 'ollama_chat/{model}'], env: { OLLAMA_API_BASE: '{ollama}' } },
    },
  },
  {
    id: 'opencode',
    name: 'OpenCode',
    description: 'opencode run, continuing the last session in the working directory. Needs an "lmstudio" or "ollama" provider in opencode.json.',
    mode: 'turn',
    command: 'opencode',
    args: ['run', '{modelArgs}', '{prompt}'],
    continueArgs: ['run', '--continue', '{modelArgs}', '{prompt}'],
    backends: {
      [LMSTUDIO]: { modelArgs: ['--model', 'lmstudio/{model}'] },
      [OLLAMA]: { modelArgs: ['--model', 'ollama/{model}'] },
    },
  },
  {
    id: 'codex',
    name: 'Codex (local OSS)',
    description: 'codex exec --oss against a local model.',
    mode: 'turn',
    command: 'codex',
    args: ['exec', '--oss', '--local-provider', '{provider}', '--sandbox', 'workspace-write', '--skip-git-repo-check', '{modelArgs}', '{prompt}'],
    continueArgs: ['exec', '--oss', '--local-provider', '{provider}', '--sandbox', 'workspace-write', '--skip-git-repo-check', '{modelArgs}', 'resume', '--last', '{prompt}'],
    modelArgs: ['-m', '{model}'],
    backends: { [LMSTUDIO]: {}, [OLLAMA]: {} },
  },
  {
    id: 'goose',
    name: 'Goose',
    description: 'goose run with a named session per phone session.',
    mode: 'turn',
    command: 'goose',
    args: ['run', '--name', 'harness-remote-{session}', '--text', '{prompt}'],
    continueArgs: ['run', '--name', 'harness-remote-{session}', '--resume', '--text', '{prompt}'],
    backends: {
      [OLLAMA]: { modelEnv: { GOOSE_PROVIDER: 'ollama', GOOSE_MODEL: '{model}', OLLAMA_HOST: '{ollama}' } },
    },
  },
  {
    id: 'claude-ollama',
    name: 'Claude Code (Ollama)',
    description: 'claude -p pointed at Ollama\'s Anthropic-compatible endpoint.',
    mode: 'turn',
    command: 'claude',
    args: ['-p', '--permission-mode', 'acceptEdits', '{modelArgs}', '{prompt}'],
    continueArgs: ['-p', '--continue', '--permission-mode', 'acceptEdits', '{modelArgs}', '{prompt}'],
    backends: {
      [OLLAMA]: {
        modelArgs: ['--model', '{model}'],
        env: { ANTHROPIC_BASE_URL: '{ollama}', ANTHROPIC_AUTH_TOKEN: 'ollama', ANTHROPIC_API_KEY: '' },
        modelEnv: { ANTHROPIC_DEFAULT_HAIKU_MODEL: '{model}' },
      },
    },
  },
  {
    id: 'ollama-chat',
    name: 'Ollama chat',
    description: 'Plain interactive ollama run session (no tools).',
    mode: 'interactive',
    pty: true,
    command: 'ollama',
    args: ['run', '{model}'],
    backends: { [OLLAMA]: {} },
    requiresModel: true,
  },
  {
    id: 'shell',
    name: 'Shell',
    description: 'Interactive login shell. Disabled by default: it gives the phone full shell access.',
    mode: 'interactive',
    pty: true,
    command: process.env.SHELL || '/bin/sh',
    args: ['-i'],
    disabled: true,
  },
];

function providersOf(h) {
  if (h.backends) return Object.keys(h.backends).filter((k) => PROVIDERS.includes(k));
  if (Array.isArray(h.models)) return h.models;
  return h.models ? [h.models] : [];
}

function withBackend(h, provider) {
  const b = (h.backends && h.backends[provider]) || {};
  return { ...h, ...b, env: { ...(h.env || {}), ...(b.env || {}) }, modelEnv: { ...(h.modelEnv || {}), ...(b.modelEnv || {}) } };
}

function harnessList() {
  const byId = new Map(PRESETS.map((h) => [h.id, { ...h, preset: true }]));
  for (const h of config.harnesses) {
    if (!h || !h.id) continue;
    const base = byId.get(h.id) || {};
    const merged = { ...base, ...h };
    if (h.models && !h.backends) delete merged.backends;
    byId.set(h.id, merged);
  }
  return [...byId.values()].filter((h) => !h.disabled && h.command);
}

const whichCache = new Map();
function which(cmd) {
  if (whichCache.has(cmd)) return whichCache.get(cmd);
  const pathext = (process.env.PATHEXT || '.COM;.EXE;.BAT;.CMD').toLowerCase().split(';').filter(Boolean);
  const exts = IS_WIN && !pathext.includes(path.extname(cmd).toLowerCase()) ? pathext : [''];
  const isFile = (p) => { try { fs.accessSync(p, fs.constants.X_OK); return fs.statSync(p).isFile(); } catch { return false; } };
  let found = null;
  if (cmd.includes('/') || (IS_WIN && cmd.includes('\\'))) {
    found = exts.map((e) => cmd + e).find(isFile) || null;
  } else {
    for (const dir of (process.env.PATH || '').split(path.delimiter)) {
      if (!dir) continue;
      found = exts.map((e) => path.join(dir, cmd + e)).find(isFile) || null;
      if (found) break;
    }
  }
  whichCache.set(cmd, found);
  return found;
}

// Windows: npm installs CLIs as .cmd shims. Running those needs cmd.exe, which can't safely carry
// arbitrary prompt text, so run the script the shim points at with node (or the .exe) directly.
function shimTarget(file) {
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch { return null; }
  const m = [...text.matchAll(/"%~?dp0%?\\?([^"%]+)"\s+%\*/gi)].pop();
  if (!m) return null;
  const target = path.join(path.dirname(file), m[1]);
  return fs.existsSync(target) ? target : null;
}

function resolveLaunch(file, args) {
  if (!IS_WIN || !/\.(cmd|bat)$/i.test(file)) return [file, args];
  const target = shimTarget(file);
  if (!target) throw new Error(`${path.basename(file)} is a batch file the bridge can't run safely. Set "command" to the .exe or .js it launches.`);
  if (/\.exe$/i.test(target)) return [target, args];
  return [process.execPath, [target, ...args]];
}

function unavailableReason(h) {
  const file = which(h.command);
  if (!file) return `${h.command} was not found on PATH`;
  if (h.pty && IS_WIN) return 'Needs a terminal (PTY), which the bridge does not support on Windows yet';
  try { resolveLaunch(file, []); } catch (e) { return e.message; }
  return null;
}

function publicHarness(h) {
  return {
    id: h.id,
    name: h.name || h.id,
    description: h.description || '',
    mode: h.mode === 'interactive' || h.mode === 'rpc' ? h.mode : 'turn',
    models: providersOf(h).length ? providersOf(h) : null,
    requiresModel: !!h.requiresModel,
    effort: !!h.effort,
    images: h.mode === 'rpc' ? 'native' : 'files',
    available: !unavailableReason(h),
    reason: unavailableReason(h),
    command: h.command,
  };
}

if (argv.includes('--print-config')) {
  console.log(JSON.stringify({ configPath, roots: config.roots, harnesses: harnessList().map(publicHarness) }, null, 2));
  process.exit(0);
}

// ---------------------------------------------------------------- templating

function fill(str, vars) {
  return String(str).replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
}

function buildArgs(template, h, vars) {
  const out = [];
  for (const a of template) {
    if (a === '{modelArgs}') {
      if (vars.model) for (const m of h.modelArgs || []) out.push(fill(m, vars));
    } else if (a === '{thinkingArgs}') {
      if (vars.thinking) out.push('--thinking', vars.thinking);
    } else {
      out.push(fill(a, vars));
    }
  }
  return out;
}

function buildEnv(h, vars) {
  const env = { ...process.env };
  for (const [k, v] of Object.entries(h.env || {})) env[k] = fill(v, vars);
  if (vars.model) for (const [k, v] of Object.entries(h.modelEnv || {})) env[k] = fill(v, vars);
  for (const [k, v] of Object.entries(env)) if (v === '') delete env[k];
  if (env.NO_COLOR == null) env.NO_COLOR = '1';
  // Commands the agent runs inherit this, so Python tools print as they go instead of when they exit.
  if (env.PYTHONUNBUFFERED == null) env.PYTHONUNBUFFERED = '1';
  env.FORCE_COLOR = '0';
  return env;
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

function wrapPty(cmd, args) {
  const line = `stty cols 100 rows 40 2>/dev/null; exec ${[cmd, ...args].map(shQuote).join(' ')}`;
  if (process.platform === 'linux') return ['script', ['-qfec', line, '/dev/null']];
  if (process.platform === 'darwin') return ['script', ['-q', '/dev/null', '/bin/sh', '-c', line]];
  return [cmd, args];
}

// ---------------------------------------------------------------- paths

const realpath = (p) => (IS_WIN ? fs.realpathSync.native(p) : fs.realpathSync(p));
const samePath = (p) => (IS_WIN ? p.toLowerCase() : p);

function realRoots() {
  return config.roots.map((r) => { try { return realpath(r.replace(/^~(?=$|[\\/])/, os.homedir())); } catch { return null; } }).filter(Boolean);
}

function insideRoots(p) {
  let real;
  try { real = realpath(p); } catch { return null; }
  for (const root of realRoots()) {
    const r = samePath(root.endsWith(path.sep) ? root : root + path.sep);
    if (samePath(real) === samePath(root) || samePath(real).startsWith(r)) return real;
  }
  return null;
}

function listDir(p) {
  const dir = insideRoots(p || realRoots()[0] || os.homedir());
  if (!dir) throw new Error('Path is outside the allowed roots');
  const entries = fs.readdirSync(dir, { withFileTypes: true })
    .filter((e) => !e.name.startsWith('.'))
    .filter((e) => e.isDirectory() || (e.isSymbolicLink() && safeIsDir(path.join(dir, e.name))))
    .map((e) => e.name)
    .sort((a, b) => a.localeCompare(b))
    .slice(0, 500);
  const isRoot = realRoots().some((r) => samePath(r) === samePath(dir));
  return { path: dir, parent: isRoot ? null : path.dirname(dir), dirs: entries, git: fs.existsSync(path.join(dir, '.git')) };
}

function safeIsDir(p) {
  try { return fs.statSync(p).isDirectory(); } catch { return false; }
}

// ---------------------------------------------------------------- models

async function fetchJson(url, ms = 1500) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { signal: ctl.signal });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } finally {
    clearTimeout(t);
  }
}

function lmsPath() {
  const bundled = path.join(os.homedir(), '.lmstudio', 'bin', IS_WIN ? 'lms.exe' : 'lms');
  return which('lms') || (fs.existsSync(bundled) ? bundled : null);
}

const visionModels = new Set();

async function lmStudioModels() {
  const isChat = (m) => !m.type || m.type === 'llm' || m.type === 'vlm';
  try {
    const j = await fetchJson(`${config.lmstudio}/api/v0/models`);
    for (const m of j.data || []) if (m.type === 'vlm') visionModels.add(`${LMSTUDIO}/${m.id}`);
    return (j.data || []).filter(isChat).map((m) => ({
      name: m.id, size: 0, family: m.arch || '', vision: m.type === 'vlm',
      params: [m.quantization, m.type === 'vlm' ? 'vision' : '', m.state === 'loaded' ? 'loaded' : ''].filter(Boolean).join(' · '),
    }));
  } catch {
    const j = await fetchJson(`${config.lmstudio}/v1/models`);
    return (j.data || []).filter((m) => !/embed/i.test(m.id)).map((m) => ({ name: m.id, size: 0, family: '', params: '' }));
  }
}

async function listModels() {
  const result = { ollama: [], lmstudio: [], errors: {}, lms: !!lmsPath() };
  await Promise.all([
    fetchJson(`${config.ollama}/api/tags`).then((j) => {
      result.ollama = (j.models || []).map((m) => ({ name: m.name, size: m.size || 0, family: m.details?.family || '', params: m.details?.parameter_size || '' }));
    }).catch(() => { result.errors.ollama = `Ollama is not answering at ${config.ollama}.`; }),
    lmStudioModels().then((list) => { result.lmstudio = list; }).catch(() => {
      result.errors.lmstudio = `LM Studio's server is not running at ${config.lmstudio}. Start it in LM Studio (Developer → Start server) or with "lms server start".`;
    }),
  ]);
  return result;
}

function startLmStudio() {
  const lms = lmsPath();
  if (!lms) return Promise.reject(new Error('The lms command was not found. Start the server from the LM Studio app instead.'));
  return new Promise((resolve, reject) => {
    let out = '';
    const child = spawn(lms, ['server', 'start'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const timer = setTimeout(() => { child.kill(); reject(new Error('lms server start timed out')); }, 60000);
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(stripAnsi(out).trim());
      else reject(new Error(stripAnsi(out).trim() || `lms exited with code ${code}`));
    });
  });
}

// pi only sees models listed in its models.json, so add the one the phone picked.
function ensurePiModel(spec, model, vars, vision) {
  const envDir = process.env.PI_CODING_AGENT_DIR;
  const dir = envDir ? path.resolve(envDir.replace(/^~(?=$|[\\/])/, os.homedir())) : path.join(os.homedir(), '.pi', 'agent');
  const file = path.join(dir, 'models.json');
  let data = {};
  if (fs.existsSync(file)) {
    try { data = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) {
      throw new Error(`${file} is not plain JSON (${e.message}). Add a "${spec.key}" provider with model "${model}" by hand.`);
    }
  }
  if (!data.providers || typeof data.providers !== 'object') data.providers = {};
  let provider = data.providers[spec.key];
  let changed = false;
  if (!provider) {
    provider = data.providers[spec.key] = { baseUrl: fill(spec.baseUrl, vars), api: 'openai-completions', apiKey: spec.apiKey, models: [] };
    changed = true;
  }
  if (!Array.isArray(provider.models)) provider.models = [];
  const entry = provider.models.find((m) => m && m.id === model);
  if (!entry) {
    provider.models.push(vision ? { id: model, reasoning: true, input: ['text', 'image'] } : { id: model, reasoning: true });
    changed = true;
  } else {
    // Entries the bridge wrote before 0.4 were bare { id }: let them think. Vision models also need image input.
    if (Object.keys(entry).length === 1) { entry.reasoning = true; changed = true; }
    if (vision && !entry.input) { entry.input = ['text', 'image']; changed = true; }
  }
  if (!changed) return null;
  fs.mkdirSync(dir, { recursive: true });
  saveJson(file, data);
  return file;
}

// ---------------------------------------------------------------- pi rpc

// ---- command guard (serialized into the pi extension below; only uses path and os)
// guard:start
function analyzeCommand(command, cwd, home, platform, env) {
  const win = platform === 'win32';
  const P = win ? path.win32 : path.posix;
  const fold = (p) => (win ? p.toLowerCase() : p);
  const clean = (p) => {
    const r = P.resolve(p);
    return r.length > P.parse(r).root.length ? r.replace(/[\\/]+$/, '') : r;
  };
  const same = (a, b) => fold(clean(a)) === fold(clean(b));
  const inside = (child, parent) => {
    const c = fold(clean(child));
    const q = fold(clean(parent));
    return c === q || c.startsWith(q.endsWith(P.sep) ? q : q + P.sep);
  };
  const known = {
    HOME: home, USERPROFILE: home, HOMEPATH: home, PWD: cwd,
    APPDATA: env.APPDATA, LOCALAPPDATA: env.LOCALAPPDATA, TEMP: env.TEMP || env.TMP, TMP: env.TMP || env.TEMP,
  };
  const assigned = {};
  const lookup = (name) => {
    for (const src of [assigned, known, env]) {
      const key = Object.keys(src).find((k) => (win ? k.toLowerCase() === name.toLowerCase() : k === name));
      if (key && src[key]) return src[key];
    }
    return undefined;
  };
  const protectedNames = ['desktop', 'documents', 'downloads', 'pictures', 'music', 'videos', 'movies', 'public', 'favorites',
    'contacts', 'links', 'saved games', 'searches', 'dropbox', 'google drive', 'icloud drive', 'iclouddrive', 'appdata',
    'library', '.ssh', '.gnupg', '.config', '.local'];
  const systemDirs = win
    ? [env.SystemRoot || 'C:\\Windows', env.ProgramFiles || 'C:\\Program Files', env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', env.ProgramData || 'C:\\ProgramData', 'C:\\Users']
    : ['/bin', '/boot', '/dev', '/etc', '/lib', '/lib64', '/opt', '/proc', '/root', '/sbin', '/srv', '/sys', '/usr', '/var', '/home', '/Users', '/System', '/Applications', '/Library'];

  const result = { destructive: false, block: null, targets: [], warn: '' };

  const expand = (raw) => {
    let unresolved = false;
    let t = raw;
    if (t === '~' || t.startsWith('~/') || t.startsWith('~\\')) t = home + t.slice(1);
    t = t.replace(/\$env:([A-Za-z_][\w()]*)|\$\{([A-Za-z_]\w*)\}|\$([A-Za-z_]\w*)|%([A-Za-z_][\w()]*)%/g, (m, a, b, c, d) => {
      const v = lookup(a || b || c || d);
      if (v === undefined) { unresolved = true; return ''; }
      return v;
    });
    if (win) t = t.replace(/^\/([a-zA-Z])(\/|$)/, (m, drive) => drive.toUpperCase() + ':\\');
    return { path: t.replace(/\u0001/g, ''), unresolved };
  };

  const judge = (target) => {
    if (same(target, P.parse(P.resolve(target)).root)) return 'it is a whole drive or the filesystem root';
    if (inside(home, target)) return 'it is your user folder (or contains it)';
    const parent = P.dirname(clean(target));
    const base = P.basename(clean(target)).toLowerCase();
    if (same(parent, home) && (protectedNames.includes(base) || base.startsWith('onedrive'))) return `it is your ${P.basename(clean(target))} folder`;
    if (same(parent, P.join(home, 'AppData')) || same(parent, P.join(home, '.local'))) return `it is ${clean(target)}, which many apps depend on`;
    if (inside(cwd, target)) return 'it is the project folder (or contains it)';
    for (const d of systemDirs) if (d && inside(d, target)) return 'it is a system folder (or contains one)';
    return null;
  };

  const tokenize = (seg) => {
    const out = [];
    const re = /"((?:\\.|[^"\\])*)"|'([^']*)'|(\S+)/g;
    let m;
    while ((m = re.exec(seg))) out.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : m[3]);
    return out;
  };

  const DELETE = new Set(['rm', 'rmdir', 'rd', 'del', 'erase', 'ri', 'remove-item', 'unlink', 'shred', 'rimraf', 'trash', 'rmtree']);
  // PowerShell built-ins that cannot be reassigned: "$home = ..." fails, the script carries on, and $home
  // still means the user folder. That exact bug deleted a user's home folder.
  const readOnlyPs = ['home', 'pid', 'pshome', 'host', 'true', 'false', 'null', 'shellid', 'executioncontext', 'psversiontable', 'iswindows', 'islinux', 'ismacos', 'iscoreclr'];
  // ${NAME} becomes $NAME plus a \u0001 terminator so splitting on PowerShell braces leaves it intact.
  const segments = command.replace(/\$\{([A-Za-z_]\w*)\}/g, (m, n) => '$' + n + '\u0001')
    .split(/\s*(?:&&|\|\||;|\||\r?\n|\{|\})\s*/).filter(Boolean);
  let here = cwd;
  segments.forEach((seg, i) => {
    const ps = seg.match(/^\$([A-Za-z_][\w]*)\s*=(?!=)\s*(.*)$/);
    const sh = seg.match(/^([A-Za-z_]\w*)=(.*)$/);
    if (ps || sh) {
      const name = (ps || sh)[1];
      const value = (ps || sh)[2].trim().replace(/^(['"])(.*)\1$/, '$2');
      if (ps && readOnlyPs.includes(name.toLowerCase())) {
        result.block = result.block || `it assigns $${name}, a built-in PowerShell variable that cannot be changed (the assignment fails and $${name} keeps its original value${name.toLowerCase() === 'home' ? ', your user folder' : ''}). Use a different variable name`;
        return;
      }
      const { path: v, unresolved } = expand(value);
      if (unresolved) delete assigned[name];
      else assigned[name] = v;
      return;
    }
    let tokens = tokenize(seg);
    while (tokens.length && /^(if|elseif|else|while|foreach|for|try|finally|then|do)$/i.test(tokens[0])) {
      tokens = tokens.slice(1);
      while (tokens.length && /^\(/.test(tokens[0])) {
        let depth = 0;
        let k = 0;
        for (; k < tokens.length; k++) {
          depth += (tokens[k].match(/\(/g) || []).length - (tokens[k].match(/\)/g) || []).length;
          if (depth <= 0) break;
        }
        tokens = tokens.slice(k + 1);
      }
    }
    while (tokens.length && /^(sudo|command|exec|nohup|time|xargs|&|\.)$/i.test(tokens[0])) tokens = tokens.slice(1);
    // Look through wrappers that run another program: env, npx, npm exec, timeout, start, ...
    for (let unwrapped = true; unwrapped && tokens.length;) {
      unwrapped = false;
      const h0 = tokens[0].toLowerCase().replace(/\.(exe|cmd|bat)$/, '').replace(/^.*[\\/]/, '');
      const sub = (tokens[1] || '').toLowerCase();
      let skip = 0;
      if (h0 === 'env') skip = 1;
      else if (['npx', 'bunx', 'pnpx', 'nice', 'stdbuf', 'ionice', 'call', 'start', 'doas', 'runas', 'sudo', 'xargs', 'nohup', 'time', 'timeout'].includes(h0)) skip = 1;
      else if (['npm', 'pnpm', 'yarn', 'bun'].includes(h0) && ['exec', 'dlx', 'x'].includes(sub)) skip = 2;
      if (!skip) break;
      tokens = tokens.slice(skip);
      while (tokens.length && (/^-/.test(tokens[0]) || /^[A-Za-z_]\w*=/.test(tokens[0]) || tokens[0] === '' ||
        (win && /^\/[a-zA-Z]+$/.test(tokens[0])) || (h0 === 'timeout' && /^\d/.test(tokens[0])))) tokens = tokens.slice(1);
      unwrapped = true;
    }
    if (!tokens.length) return;
    const head = tokens[0].toLowerCase().replace(/\.(exe|cmd|bat)$/, '').replace(/^.*[\\/]/, '');
    if (['cd', 'pushd', 'set-location', 'sl', 'chdir'].includes(head)) {
      const dest = tokens.slice(1).find((t) => !/^-/.test(t) && !/^\/d$/i.test(t));
      if (!dest) { here = home; return; }
      const { path: d, unresolved } = expand(dest);
      here = unresolved || here === null ? null : P.resolve(here, d);
      return;
    }
    if (['cmd', 'powershell', 'pwsh', 'bash', 'sh', 'zsh'].includes(head)) {
      const flag = tokens.findIndex((t, j) => j > 0 && /^([/-](c|command)|-encodedcommand)$/i.test(t));
      if (flag > 0) {
        const inner = analyzeCommand(tokens.slice(flag + 1).join(' '), cwd, home, platform, env);
        if (inner.block && !result.block) result.block = inner.block;
        if (inner.destructive) { result.destructive = true; result.targets.push(...inner.targets); result.warn = result.warn || inner.warn; }
      }
      return;
    }
    if (/^(format|diskpart|mkfs(\..+)?|fdisk|cipher)$/.test(head) || (head === 'dd' && tokens.some((t) => /^of=/.test(t)))) {
      result.block = result.block || `${head} can erase whole drives`;
      return;
    }
    const lower = seg.toLowerCase();
    if ((head === 'find' && /\s-(delete|exec\s+rm)\b/.test(lower)) || (head === 'git' && /\bclean\b/.test(lower) && /\s-[a-z]*f/.test(lower)) ||
        (head === 'git' && /\breset\b.*--hard/.test(lower)) || (head === 'robocopy' && /\/(mir|purge)\b/.test(lower)) ||
        /shutil\.rmtree|os\.(remove|unlink|rmdir)|rmsync|fs\.rm\(|fs\.promises\.rm|directory\]::delete|remove-item/.test(lower) && !DELETE.has(head)) {
      result.destructive = true;
      result.warn = result.warn || 'This command can permanently delete files';
      return;
    }
    if (!DELETE.has(head)) return;
    result.destructive = true;
    result.warn = 'This permanently deletes files (they do not go to the Recycle Bin)';
    const args = [];
    for (let j = 1; j < tokens.length; j++) {
      const t = tokens[j];
      if (/^-(filter|include|exclude|erroraction|ea)$/i.test(t)) { j++; continue; }
      if (/^-/.test(t) || /^\/[a-zA-Z]$/.test(t) || /^[<>]|^\d?>/.test(t)) continue;
      args.push(t);
    }
    if (!args.length && i > 0) {
      const prev = tokenize(segments[i - 1]);
      if (/^(get-childitem|gci|ls|dir|get-item|gi)$/i.test(prev[0] || '')) args.push(...prev.slice(1).filter((t) => !/^-/.test(t)));
    }
    for (const raw of args) {
      const { path: expanded, unresolved } = expand(raw);
      if (unresolved) { result.block = result.block || `it deletes "${raw.replace(/\u0001/g, '')}", which uses a variable that is not set (an empty variable can turn this into a delete of a much bigger folder)`; continue; }
      if (!expanded.trim()) { result.block = result.block || 'its target path is empty'; continue; }
      const wild = /[*?]/.test(expanded);
      let target = expanded;
      if (wild) {
        const parts = expanded.split(/[\\/]/);
        const k = parts.findIndex((x) => /[*?]/.test(x));
        target = parts.slice(0, k).join(P.sep) || (expanded.startsWith('/') ? '/' : '.');
      }
      if (/^[a-zA-Z]:$/.test(target)) target += P.sep;
      if (here === null && !P.isAbsolute(target)) { result.block = result.block || `it deletes "${raw}" after changing to a folder that could not be worked out`; continue; }
      const resolved = P.resolve(here || cwd, target);
      const why = judge(resolved);
      result.targets.push(wild ? `${resolved}${P.sep}${expanded.split(/[\\/]/).slice(-1)[0]}` : resolved);
      if (why) result.block = result.block || `it would delete ${wild ? 'the contents of ' : ''}${clean(resolved)}: ${why}`;
    }
  });
  return result;
}
// guard:end

// Returns the heads of the segments that are NOT read-only. Empty array => the whole command only
// reads (search, list, status), so it runs without asking. Only uses string ops (serialized into pi).
function writeCommandHeads(command) {
  const FAM = {
    git: new Set(['status', 'log', 'diff', 'show', 'branch', 'remote', 'config', 'ls-files', 'ls-remote', 'rev-parse', 'describe', 'blame', 'cat-file', 'for-each-ref', 'shortlog', 'reflog', 'name-rev', 'symbolic-ref', 'var', 'grep', 'whatchanged', 'count-objects']),
    npm: new Set(['ls', 'list', 'view', 'info', 'show', 'outdated', 'root', 'prefix', 'ping', 'doctor', 'why', 'search', 'help', 'explain', 'fund', 'audit']),
    pnpm: new Set(['ls', 'list', 'view', 'info', 'outdated', 'why', 'licenses', 'audit', 'root']),
    yarn: new Set(['list', 'why', 'info', 'versions']),
    pip: new Set(['show', 'list', 'freeze', 'check', 'config']),
    pip3: new Set(['show', 'list', 'freeze', 'check', 'config']),
    cargo: new Set(['tree', 'metadata', 'search', 'version']),
    go: new Set(['version', 'env', 'list', 'doc']),
    docker: new Set(['ps', 'images', 'version', 'info', 'logs', 'inspect', 'history', 'top', 'port', 'stats', 'diff']),
    dotnet: new Set(['--version', '--info', '--list-sdks', '--list-runtimes']),
    kubectl: new Set(['get', 'describe', 'logs', 'version', 'top', 'explain', 'api-resources', 'config', 'cluster-info']),
    brew: new Set(['list', 'info', 'search', '--version', 'config', 'doctor', 'outdated', 'deps']),
  };
  const READ = new Set(['find', 'ls', 'dir', 'gci', 'get-childitem', 'cat', 'type', 'gc', 'get-content', 'head', 'tail', 'more', 'less', 'grep', 'rg', 'egrep', 'fgrep', 'ag', 'ack', 'fd', 'pwd', 'echo', 'printf', 'wc', 'which', 'where', 'whereis', 'whatis', 'tree', 'stat', 'file', 'du', 'df', 'printenv', 'date', 'whoami', 'hostname', 'uname', 'id', 'groups', 'basename', 'dirname', 'realpath', 'readlink', 'test', '[', 'true', 'false', 'sleep', 'sort', 'uniq', 'cut', 'column', 'jq', 'yq', 'xxd', 'od', 'strings', 'diff', 'cmp', 'nl', 'tac', 'rev', 'seq', 'man', 'help', 'history', 'alias', 'command', 'hash', 'getconf', 'locale', 'tty', 'ps', 'pgrep', 'lsof', 'ss', 'netstat', 'ifconfig', 'ipconfig', 'ip', 'cd', 'pushd', 'popd', 'set-location', 'sl', 'gl', 'get-location', 'select-string', 'measure-object', 'get-command', 'gcm', 'get-help', 'get-process', 'gps', 'get-item', 'gi', 'resolve-path', 'split-path', 'join-path', 'test-path', 'get-date', 'format-list', 'fl', 'format-table', 'ft', 'select-object', 'select', 'sort-object', 'where-object', 'foreach-object', 'out-string', 'out-host', 'write-output', 'write-host', 'compare-object', 'md5sum', 'sha1sum', 'sha256sum', 'cksum', 'git-lfs']);
  const ASSIGN = /^([A-Za-z_]\w*)=/;
  const heads = [];
  const segments = command.replace(/\$\{([A-Za-z_]\w*)\}/g, '$1').split(/\s*(?:&&|\|\||;|\||\r?\n|\{|\})\s*/);
  for (let seg of segments) {
    seg = seg.trim();
    if (!seg) continue;
    const redir = seg.replace(/\d*>&\d*/g, '').replace(/&>>?/g, '');
    const writesFile = />>?\s*[^\s&|]/.test(redir) || /\|\s*(tee|out-file|set-content|add-content)\b/i.test(seg);
    let toks = seg.split(/\s+/).filter(Boolean);
    while (toks.length && (/^(sudo|command|exec|nohup|time|xargs|then|do|if|elseif|else|while|for|foreach|try|finally|begin|and|or|not|!|\(|&)$/i.test(toks[0]) || ASSIGN.test(toks[0]))) toks = toks.slice(1);
    if (!toks.length) continue;
    const head = toks[0].toLowerCase().replace(/\.(exe|cmd|bat|ps1)$/, '').replace(/^.*[\\/]/, '');
    if (writesFile) { heads.push(head); continue; }
    if (head === 'find' && toks.some((t) => /^-(exec|execdir|ok|okdir|delete|fprint|fprintf|fls)$/.test(t))) { heads.push(head); continue; }
    if (READ.has(head)) continue;
    const fam = FAM[head];
    if (fam) {
      const sub = (toks.slice(1).find((t) => !t.startsWith('-')) || '').toLowerCase();
      if (fam.has(sub) || (!sub && /^-/.test(toks[1] || ''))) continue;
      heads.push(head);
      continue;
    }
    if (/^(python|python3|node|ruby|perl|php|deno|bun|java|rustc|gcc|clang)$/.test(head) && toks.slice(1).every((t) => /^(-v|--version|-V|version)$/.test(t))) continue;
    heads.push(head);
  }
  return heads;
}

function describeToolCall(name, input) {
  const a = input || {};
  if (typeof a.command === 'string') return a.command;
  if (name === 'write' && typeof a.path === 'string') return a.path + ' (' + String(a.content || '').split('\n').length + ' lines)';
  if (typeof a.path === 'string') return a.path;
  const json = JSON.stringify(a);
  return json.length > 600 ? json.slice(0, 600) + '…' : json;
}

// Reviewer: a second model call that checks a tool call against what the user asked before it runs.
// It can only add caution: a concern forces an approval prompt; it never approves what the guard blocks.
function readReviewControl() {
  try { return JSON.parse(fs.readFileSync(process.env.HARNESS_REMOTE_CONTROL, 'utf8')); } catch { return null; }
}

function reviewContext(ctx) {
  const textOf = (m) => (typeof m.content === 'string' ? m.content : (m.content || []).filter((c) => c && c.type === 'text').map((c) => c.text).join('\n'));
  try {
    const msgs = ctx.sessionManager.getBranch().filter((e) => e && e.type === 'message' && e.message).map((e) => e.message);
    const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
    const lastAssistant = [...msgs].reverse().find((m) => m.role === 'assistant' && textOf(m).trim());
    return { request: lastUser ? textOf(lastUser).slice(-2000) : '', intent: lastAssistant ? textOf(lastAssistant).slice(-800) : '' };
  } catch {
    return { request: '', intent: '' };
  }
}

async function reviewToolCall(control, ctx, toolName, callText) {
  const { request, intent } = reviewContext(ctx);
  const shell = process.platform === 'win32' ? (toolName === 'powershell' ? 'Windows PowerShell' : 'Git Bash on Windows') : 'bash';
  const system = 'You review tool calls from a coding agent BEFORE they run on the user\'s own computer. Look for real mistakes: ' +
    'paths that do not match what the user asked for; deleting, moving or overwriting more than intended; shell pitfalls ' +
    '(PowerShell automatic variables such as $home, $input, $args, $host, $error that cannot or should not be assigned; ' +
    'variables that may be empty; missing quotes around paths with spaces; scripts that keep going after an error before a destructive step); ' +
    'installing or uninstalling the wrong thing; changes outside the project folder that the user did not ask for. ' +
    'Ignore style. Reply with ONLY a JSON object: {"verdict":"ok"|"concern","reason":"one short sentence"}. ' +
    'Use "concern" only for problems that could cause damage or clearly do the wrong thing.';
  const user = `User's request:\n${request || '(unknown)'}\n\nWhat the agent said it is doing:\n${intent || '(nothing)'}\n\n` +
    `Working folder: ${ctx.cwd || process.cwd()}\nShell: ${shell}\nTool: ${toolName}\n\nTool call:\n${callText.slice(0, 6000)}`;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 120000);
  try {
    const res = await fetch(String(control.url).replace(/\/$/, '') + '/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer ' + (control.key || 'local') },
      body: JSON.stringify({ model: control.model, temperature: 0, max_tokens: 2000, stream: false, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] }),
      signal: abort.signal,
    });
    if (!res.ok) return { verdict: 'error', reason: 'Reviewer request failed (HTTP ' + res.status + ')' };
    const j = await res.json();
    const text = String((j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || '')
      .replace(/<think>[\s\S]*?<\/think>/gi, '').replace(/^[\s\S]*<\/think>/i, '');
    const m = text.match(/\{[^{}]*"verdict"[^{}]*\}/);
    if (!m) return { verdict: 'error', reason: 'Reviewer gave no verdict' };
    const v = JSON.parse(m[0]);
    return { verdict: v.verdict === 'concern' ? 'concern' : 'ok', reason: String(v.reason || '').trim().slice(0, 400) };
  } catch (e) {
    return { verdict: 'error', reason: 'Reviewer unavailable: ' + (e && e.name === 'AbortError' ? 'timed out' : (e && e.message) || e) };
  } finally {
    clearTimeout(timer);
  }
}

function piGuardMain(pi) {
  const READ_ONLY = new Set(['read', 'grep', 'find', 'ls']);
  const always = new Set();
  const allowedHeads = new Set();
  let allowAllWrites = false;
  pi.on('tool_call', async (event, ctx) => {
    const name = event.toolName;
    const input = event.input || {};
    const cwd = ctx.cwd || process.cwd();
    const asking = process.env.HARNESS_REMOTE_PERMISSIONS === 'ask';
    const shell = typeof input.command === 'string';

    let review = null;
    const runReview = async (callText) => {
      const control = readReviewControl();
      if (!control || !control.review || !control.url || !control.model) return null;
      ctx.ui.notify('[review:start] ' + name, 'info');
      const r = await reviewToolCall(control, ctx, name, callText);
      ctx.ui.notify('[review:' + r.verdict + '] ' + r.reason, r.verdict === 'concern' ? 'warning' : 'info');
      return r;
    };
    const reviewLine = () => (review && review.verdict !== 'error' ? '\n\nReviewer: ' + (review.verdict === 'concern' ? '⚠ ' : 'OK. ') + review.reason : '');
    const concernPrompt = async (what) => {
      const choice = await ctx.ui.select('⚠ The reviewer has a concern\n' + review.reason + '\n\n' + what, ['No', 'Yes, run it anyway']);
      if (choice === 'Yes, run it anyway') return undefined;
      return { block: true, reason: 'The user declined after the reviewer raised a concern: ' + review.reason };
    };

    if (shell) {
      const a = analyzeCommand(input.command, cwd, os.homedir(), process.platform, process.env);
      if (a.block) {
        ctx.ui.notify('Blocked a command: ' + a.block, 'warning');
        return { block: true, reason: 'Harness Remote blocked this command because ' + a.block + '. Do not retry or work around this. Tell the user exactly what you wanted to delete and why, and let them do it themselves.' };
      }
      const heads = writeCommandHeads(input.command);
      if (a.destructive || heads.length) review = await runReview(input.command);
      if (a.destructive) {
        const detail = (a.targets.length ? 'Deletes:\n' + a.targets.map((t) => '  ' + t).join('\n') + '\n\n' : '') + 'Command:\n' + input.command + reviewLine();
        const choice = await ctx.ui.select('⚠ ' + a.warn + '\n' + detail, ['No', 'Yes, run it']);
        if (choice === 'Yes, run it') return undefined;
        return { block: true, reason: 'The user declined this destructive command from their phone.' };
      }
      if (!heads.length) return undefined;                 // read-only: searching, listing, status, reading
      if (review && review.verdict === 'concern') return concernPrompt('Command:\n' + input.command);
      if (!asking) return undefined;                       // auto mode runs non-delete commands
      if (allowAllWrites || heads.every((h) => allowedHeads.has(h))) return undefined;
      const optHead = 'Allow ' + heads[0] + ' commands this session';
      const optAll = 'Allow all non-delete commands this session';
      const choice = await ctx.ui.select('Run this command?\n' + input.command + reviewLine(), ['Yes', optHead, optAll, 'No']);
      if (choice === optAll) { allowAllWrites = true; return undefined; }
      if (choice === optHead) { heads.forEach((h) => allowedHeads.add(h)); return undefined; }
      if (choice === 'Yes') return undefined;
      return { block: true, reason: 'The user declined this command from their phone.' };
    }
    if (READ_ONLY.has(name)) return undefined;
    const tool = pi.getAllTools().find((t) => t.name === name);
    if (tool && tool.annotations && tool.annotations.readOnlyHint === true) return undefined;
    const callText = (name === 'write' || name === 'edit') && typeof input.path === 'string'
      ? input.path + '\n' + String(input.content || input.newText || input.new_text || JSON.stringify(input)).slice(0, 4000)
      : describeToolCall(name, input);
    review = await runReview(callText);
    if ((name === 'write' || name === 'edit') && typeof input.path === 'string') {
      const target = path.resolve(cwd, input.path);
      const rel = path.relative(cwd, target);
      if (rel.startsWith('..') || path.isAbsolute(rel)) {
        const choice = await ctx.ui.select('⚠ Change a file outside the project folder?\n' + target + reviewLine(), ['No', 'Yes']);
        if (choice === 'Yes') return undefined;
        return { block: true, reason: 'The user declined a change outside the project folder.' };
      }
    }
    if (review && review.verdict === 'concern') return concernPrompt(describeToolCall(name, input));
    if (!asking || always.has(name)) return undefined;
    const yesAll = "Yes, and don't ask again for " + name + ' this session';
    const choice = await ctx.ui.select('Allow ' + name + '?\n' + describeToolCall(name, input) + reviewLine(), ['Yes', yesAll, 'No']);
    if (choice === yesAll) always.add(name);
    if (choice === 'Yes' || choice === yesAll) return undefined;
    return { block: true, reason: 'The user declined this ' + name + ' call from their phone.' };
  });
}

// Loaded into pi with -e: blocks commands that would wipe user data and asks the phone before the rest.
const PI_PERMISSION_EXTENSION = [
  '// Written by Harness Bridge. Guards destructive commands and asks the Harness Remote app before pi changes things.',
  "import path from 'node:path';",
  "import os from 'node:os';",
  "import fs from 'node:fs';",
  analyzeCommand.toString(),
  writeCommandHeads.toString(),
  describeToolCall.toString(),
  readReviewControl.toString(),
  reviewContext.toString(),
  reviewToolCall.toString(),
  'export default ' + piGuardMain.toString(),
  '',
].join('\n\n');

function piPermissionExtension() {
  const file = path.join(path.dirname(configPath), 'pi-permissions.ts');
  let current = null;
  try { current = fs.readFileSync(file, 'utf8'); } catch {}
  if (current !== PI_PERMISSION_EXTENSION) fs.writeFileSync(file, PI_PERMISSION_EXTENSION);
  return file;
}

function rpcWrite(p, obj) {
  try { p.child.stdin.write(JSON.stringify(obj) + '\n'); } catch {}
}

function toolSummary(args) {
  const a = args || {};
  const pick = [a.command, a.path, a.file_path, a.pattern, a.url, a.query].find((v) => typeof v === 'string');
  const text = pick !== undefined ? pick : JSON.stringify(a);
  return text.length > 300 ? `${text.slice(0, 300)}…` : text;
}

function contentText(result) {
  return ((result && result.content) || []).filter((c) => c && c.type === 'text').map((c) => c.text).join('\n');
}

// Partial JSON of a tool call that is still being written: show the interesting field as text.
function previewToolArgs(args) {
  const field = (name) => {
    const m = args.match(new RegExp(`"${name}"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)`));
    if (!m) return null;
    return m[1].replace(/\\(u[0-9a-fA-F]{4}|.)/g, (x, c) =>
      (c[0] === 'u' && c.length === 5 ? String.fromCharCode(parseInt(c.slice(1), 16)) : c === 'n' ? '\n' : c === 't' ? '\t' : c === 'r' ? '' : c));
  };
  const body = ['command', 'content', 'new_text', 'newText', 'text', 'code', 'script'].map(field).find((v) => v !== null);
  const where = ['path', 'file_path', 'url', 'query'].map(field).find((v) => v !== null);
  const text = body !== undefined ? (where ? `${where}\n${body}` : body) : where !== undefined ? where : args;
  return text.length > 3000 ? text.slice(-3000) : text;
}

function toolResultText(result) {
  const text = contentText(result);
  return text.length > 4000 ? `${text.slice(0, 4000)}\n… (${text.length - 4000} more characters)` : text;
}

// Turns pi's RPC event stream into transcript events.
function piRecordHandler(s, p) {
  p.stream = { kind: null, text: '' };
  p.asks = new Map();
  p.progress = new Map();
  // Live tool output (cumulative, stdout and stderr) goes to viewers only; the final result is stored.
  const sendProgress = () => {
    p.progressTimer = null;
    for (const [toolId, text] of p.progress) broadcastProgress(s, toolId, text);
    p.progress.clear();
  };
  const streamFlush = () => {
    clearTimeout(p.streamTimer);
    p.streamTimer = null;
    if (p.stream.text) pushEvent(s, { kind: p.stream.kind, text: p.stream.text });
    p.stream = { kind: null, text: '' };
  };
  const streamAppend = (kind, delta) => {
    if (!delta) return;
    if (p.stream.kind !== kind) streamFlush();
    p.stream.kind = kind;
    p.stream.text += delta;
    if (!p.streamTimer) p.streamTimer = setTimeout(streamFlush, FLUSH_MS);
  };
  p.streamFlush = streamFlush;

  // What pi is doing right now, so the phone never shows a bare "Working…": waiting on the model,
  // thinking, writing, preparing a tool call (arguments stream in), running a tool, retrying.
  const calls = new Map();
  let activityTimer = null;
  const setActivity = (phase, label) => {
    if (s.activity && s.activity.phase === phase && s.activity.label === label) return;
    s.activity = phase ? { phase, label, chars: 0, since: Date.now() } : null;
    clearTimeout(activityTimer);
    activityTimer = null;
    broadcastActivity(s);
  };
  const addChars = (n) => {
    if (!s.activity) return;
    s.activity.chars += n;
    if (!activityTimer) activityTimer = setTimeout(() => { activityTimer = null; broadcastActivity(s); }, 1000);
  };
  const waitingLabel = () => `Waiting for ${s.model || 'the model'}`;
  return (o) => {
    switch (o.type) {
      case 'agent_start':
        if (s.state !== 'waiting') setState(s, 'running');
        setActivity('waiting', waitingLabel());
        return;
      case 'turn_start':
        setActivity('waiting', waitingLabel());
        return;
      case 'agent_settled':
        setActivity(null);
        calls.clear();
        streamFlush();
        if (o.aborted) pushEvent(s, { kind: 'status', text: 'Interrupted' });
        p.asks.clear();
        setState(s, 'idle');
        if (p.afterAbort) {
          const next = p.afterAbort;
          p.afterAbort = null;
          rpcWrite(p, { type: 'prompt', ...next });
          setState(s, 'running');
        } else if (p.restartWhenIdle) quietKill(p);
        return;
      case 'message_update': {
        const e = o.assistantMessageEvent || {};
        if (e.type === 'text_delta') {
          setActivity('writing', 'Writing');
          addChars((e.delta || '').length);
          streamAppend('text', e.delta);
        } else if (e.type === 'thinking_delta') {
          setActivity('thinking', 'Thinking');
          addChars((e.delta || '').length);
          streamAppend('think', e.delta);
        } else if (e.type === 'toolcall_start') {
          streamFlush();
          calls.set(e.contentIndex, { id: e.id, name: e.toolName, args: '' });
          setActivity('calling', `Preparing ${e.toolName}`);
          pushEvent(s, { kind: 'tool', toolId: e.id, tool: e.toolName, text: '', preparing: true });
        } else if (e.type === 'toolcall_delta') {
          const call = calls.get(e.contentIndex);
          if (!call || !e.delta) return;
          call.args += e.delta;
          addChars(e.delta.length);
          p.progress.set(call.id, previewToolArgs(call.args));
          if (!p.progressTimer) p.progressTimer = setTimeout(sendProgress, 250);
        }
        return;
      }
      case 'message_end': {
        const m = o.message || {};
        if (m.role === 'assistant' && m.stopReason === 'error') {
          streamFlush();
          pushEvent(s, { kind: 'err', text: `Model error: ${m.errorMessage || 'unknown error'}\n` });
        }
        return;
      }
      case 'tool_execution_start':
        if (!o.parentToolCallId) { p.lastTool = o.toolName; setActivity('running', `Running ${o.toolName}`); }
        streamFlush();
        pushEvent(s, { kind: 'tool', toolId: o.toolCallId, tool: o.toolName, text: toolSummary(o.args), parent: o.parentToolCallId || undefined });
        return;
      case 'tool_execution_update': {
        const text = contentText(o.partialResult);
        p.progress.set(o.toolCallId, text.length > 3000 ? text.slice(-3000) : text);
        if (!p.progressTimer) p.progressTimer = setTimeout(sendProgress, 250);
        return;
      }
      case 'tool_execution_end':
        if (!o.parentToolCallId) setActivity('waiting', waitingLabel());
        p.progress.delete(o.toolCallId);
        streamFlush();
        pushEvent(s, { kind: 'toolres', toolId: o.toolCallId, tool: o.toolName, isError: !!o.isError, durationMs: o.durationMs || 0, text: toolResultText(o.result) });
        return;
      case 'auto_retry_start':
        setActivity('retry', `Retrying (${o.attempt}/${o.maxAttempts})`);
        pushEvent(s, { kind: 'status', text: `Retrying (${o.attempt}/${o.maxAttempts}): ${o.errorMessage || ''}` });
        return;
      case 'compaction_start':
        setActivity('compacting', 'Compacting the conversation');
        pushEvent(s, { kind: 'status', text: 'Compacting the conversation to fit the context window…' });
        return;
      case 'extension_ui_request':
        if (['select', 'confirm', 'input', 'editor'].includes(o.method)) {
          streamFlush();
          p.asks.set(o.id, o.method);
          pushEvent(s, {
            kind: 'ask', askId: o.id, method: o.method, text: o.title || '', message: o.message || '',
            options: Array.isArray(o.options) ? o.options : [], placeholder: o.placeholder || o.prefill || '',
          });
          setState(s, 'waiting');
        } else if (o.method === 'notify' && o.message) {
          const rv = /^\[review:(start|ok|concern|error)\]\s*([\s\S]*)$/.exec(o.message);
          if (!rv) pushEvent(s, { kind: 'status', text: o.message });
          else if (rv[1] === 'start') setActivity('reviewing', `Reviewer checking ${rv[2]}`);
          else {
            pushEvent(s, { kind: 'review', verdict: rv[1], text: rv[2] });
            setActivity('running', `Running ${p.lastTool || 'tool'}`);
          }
        }
        return;
      case 'extension_error':
        pushEvent(s, { kind: 'err', text: `Extension error (${o.event}): ${o.error}\n` });
        return;
      case 'response':
        if (o.success === false) pushEvent(s, { kind: 'err', text: `pi rejected ${o.command}: ${o.error || 'unknown error'}\n` });
        return;
      default:
    }
  };
}

function answerAsk(s, askId, msg) {
  const p = procs.get(s.id);
  if (!p || !p.asks || !p.asks.has(askId)) throw new Error('That question is no longer waiting for an answer');
  const method = p.asks.get(askId);
  p.asks.delete(askId);
  const resp = { type: 'extension_ui_response', id: askId };
  let shown;
  if (msg.cancelled) { resp.cancelled = true; shown = 'Cancelled'; }
  else if (method === 'confirm') { resp.confirmed = !!msg.confirmed; shown = msg.confirmed ? 'Yes' : 'No'; }
  else { resp.value = String(msg.value ?? ''); shown = resp.value; }
  rpcWrite(p, resp);
  pushEvent(s, { kind: 'answer', askId, text: shown });
  if (!p.asks.size && s.state === 'waiting') setState(s, 'running');
}

// ---------------------------------------------------------------- sessions

const sessions = new Map();
for (const s of loadJson(sessionsPath, [])) {
  if (!s || !s.id) continue;
  s.state = s.state === 'running' || s.state === 'waiting' ? 'idle' : s.state || 'idle';
  if (!s.events) s.events = [];
  if (s.provider == null) s.provider = OLLAMA;
  if (!s.seq) s.seq = s.events.length ? s.events[s.events.length - 1].seq : 0;
  sessions.set(s.id, s);
}
const procs = new Map();

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const data = [...sessions.values()].map(({ id, title, harness, cwd, model, provider, thinking, permissions, review, reviewModel, reviewProvider, createdAt, updatedAt, turns, state, seq, events }) => ({ id, title, harness, cwd, model, provider, thinking, permissions, review, reviewModel, reviewProvider, createdAt, updatedAt, turns, state, seq, events }));
    try { saveJson(sessionsPath, data, 0o600); } catch (e) { console.error('save failed:', e.message); }
  }, 1000);
}

function summary(s) {
  const last = [...s.events].reverse().find((e) => ['user', 'out', 'text', 'ask', 'tool'].includes(e.kind));
  return {
    id: s.id, title: s.title, harness: s.harness, cwd: s.cwd, model: s.model, provider: s.provider || '', thinking: s.thinking || '', permissions: s.permissions || 'ask', review: !!s.review, reviewModel: s.reviewModel || '', runningSince: s.runningSince || 0,
    createdAt: s.createdAt, updatedAt: s.updatedAt, turns: s.turns, state: s.state, seq: s.seq,
    preview: last ? stripAnsi(last.text || '').trim().slice(-160) : '',
  };
}

function stripAnsi(s) {
  return s.replace(/\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[@-Z\\-_]/g, '');
}

function pushEvent(s, ev) {
  ev.seq = ++s.seq;
  ev.ts = Date.now();
  s.events.push(ev);
  if (s.events.length > MAX_EVENTS) s.events.splice(0, s.events.length - MAX_EVENTS);
  s.updatedAt = ev.ts;
  broadcastEvent(s, ev);
  scheduleSave();
}

function setState(s, state) {
  if (s.state === state) return;
  if (state === 'running' && s.state !== 'waiting') s.runningSince = Date.now();
  if (state === 'idle' || state === 'exited') s.runningSince = 0;
  s.state = state;
  s.updatedAt = Date.now();
  broadcastSessions();
  scheduleSave();
}

function createSession({ harness, cwd, model, provider, title, permissions, review }) {
  const h = harnessList().find((x) => x.id === harness);
  if (!h) throw new Error(`Unknown harness: ${harness}`);
  const reason = unavailableReason(h);
  if (reason) throw new Error(`${h.name || h.id}: ${reason}`);
  if (h.requiresModel && !model) throw new Error(`${h.name || h.id} needs a model`);
  const providers = providersOf(h);
  const backend = providers.includes(provider) ? provider : providers[0] || '';
  const dir = insideRoots(cwd || realRoots()[0] || os.homedir());
  if (!dir || !safeIsDir(dir)) throw new Error('Working directory is missing or outside the allowed roots');
  const id = crypto.randomBytes(6).toString('hex');
  const now = Date.now();
  const s = {
    id, harness: h.id, cwd: dir, model: model || '', provider: backend, permissions: permissions === 'auto' ? 'auto' : 'ask', review: review !== false && h.mode === 'rpc', reviewModel: '', reviewProvider: '', turns: 0, state: 'idle', seq: 0, events: [],
    title: (title || '').trim() || `${h.name || h.id} · ${path.basename(dir)}`,
    createdAt: now, updatedAt: now,
  };
  sessions.set(id, s);
  const via = backend === LMSTUDIO ? ' (LM Studio)' : backend === OLLAMA ? ' (Ollama)' : '';
  pushEvent(s, { kind: 'status', text: `Session created: ${h.name || h.id} in ${dir}${model ? ` with ${model}${via}` : ''}` });
  if (h.mode === 'interactive') startProcess(s, h, null);
  broadcastSessions();
  return s;
}

function startProcess(s, harness, prompt) {
  const h = withBackend(harness, s.provider);
  const vars = { prompt: prompt ?? '', model: s.model, provider: s.provider || '', thinking: s.thinking || '', session: s.id, cwd: s.cwd, ollama: config.ollama, lmstudio: config.lmstudio };
  const rpc = h.mode === 'rpc';
  if ((h.args || []).includes('{piPermissionExtension}')) {
    try { vars.piPermissionExtension = piPermissionExtension(); } catch (e) {
      pushEvent(s, { kind: 'err', text: `Could not write the permission extension: ${e.message}\n` });
      return null;
    }
  }
  if (h.piProvider && s.model) {
    try {
      const file = ensurePiModel(h.piProvider, s.model, vars, visionModels.has(`${s.provider}/${s.model}`));
      if (file) pushEvent(s, { kind: 'status', text: `Added ${h.piProvider.key}/${s.model} to ${file}` });
    } catch (e) {
      pushEvent(s, { kind: 'err', text: `${e.message}\n` });
    }
  }
  const template = h.mode === 'turn' && s.turns > 0 && h.continueArgs ? h.continueArgs : h.args || [];
  let cmd = which(h.command) || h.command;
  let args = buildArgs(template, h, vars);
  if (h.pty) [cmd, args] = wrapPty(cmd, args);
  try {
    [cmd, args] = resolveLaunch(cmd, args);
  } catch (e) {
    pushEvent(s, { kind: 'exit', code: -1, text: e.message });
    setState(s, h.mode === 'interactive' ? 'exited' : 'idle');
    return null;
  }
  const interactive = h.mode === 'interactive';
  const stdinMode = h.stdin || (interactive || rpc || h.pty ? 'pipe' : 'ignore');
  const env = buildEnv(h, vars);
  if (rpc) {
    env.HARNESS_REMOTE_PERMISSIONS = s.permissions === 'auto' ? 'auto' : 'ask';
    env.HARNESS_REMOTE_CONTROL = controlPath(s);
    writeControl(s);
  }
  if (h.pty) env.TERM = env.TERM && env.TERM !== 'dumb' ? env.TERM : 'xterm-256color';

  let child;
  try {
    child = spawn(cmd, args, { cwd: s.cwd, env, stdio: [stdinMode, 'pipe', 'pipe'], detached: !IS_WIN, windowsHide: true });
  } catch (e) {
    pushEvent(s, { kind: 'err', text: `Failed to start ${h.command}: ${e.message}\n` });
    setState(s, 'idle');
    return null;
  }

  const p = { child, harness: h, rpc, buf: { out: '', err: '' }, flushTimer: null, waitTimer: null, tail: '' };
  procs.set(s.id, p);
  if (!rpc) setState(s, 'running');

  const flush = () => {
    p.flushTimer = null;
    for (const kind of ['out', 'err']) {
      if (!p.buf[kind]) continue;
      const text = p.buf[kind];
      p.buf[kind] = '';
      pushEvent(s, { kind, text });
      p.tail = (p.tail + stripAnsi(text)).slice(-400);
    }
    if (rpc) return;
    if (s.state === 'waiting') setState(s, 'running');
    clearTimeout(p.waitTimer);
    p.waitTimer = setTimeout(() => {
      if (procs.get(s.id) === p && !child.killed && child.exitCode === null && WAITING_RE.test(p.tail.replace(/\s+$/, ' ').trimEnd())) {
        setState(s, 'waiting');
      }
    }, WAITING_MS);
  };
  const onData = (kind) => (chunk) => {
    p.buf[kind] += chunk.toString('utf8');
    if (!p.flushTimer) p.flushTimer = setTimeout(flush, FLUSH_MS);
  };
  // ignoreStderr: regexes for expected noise lines, matched per complete line.
  const ignore = (h.ignoreStderr || []).map((r) => new RegExp(r));
  const noise = (line) => ignore.some((r) => r.test(stripAnsi(line).replace(/\r$/, '')));
  p.errPartial = '';
  const onErr = (chunk) => {
    if (!ignore.length) return onData('err')(chunk);
    const lines = (p.errPartial + chunk.toString('utf8')).split('\n');
    p.errPartial = lines.pop();
    const kept = lines.filter((l) => !noise(l));
    if (kept.length) onData('err')(kept.join('\n') + '\n');
  };
  if (rpc) {
    const onRecord = piRecordHandler(s, p);
    let line = '';
    child.stdout.on('data', (chunk) => {
      const parts = (line + chunk.toString('utf8')).split('\n');
      line = parts.pop();
      for (const raw of parts) {
        const text = raw.replace(/\r$/, '');
        if (!text.trim()) continue;
        let o;
        try { o = JSON.parse(text); } catch { onData('out')(text + '\n'); continue; }
        try { onRecord(o); } catch (e) { pushEvent(s, { kind: 'err', text: `Could not handle a pi event: ${e.message}\n` }); }
      }
    });
  } else {
    child.stdout.on('data', onData('out'));
  }
  child.stderr.on('data', onErr);
  child.stdin?.on('error', () => {});
  let done = false;
  const finish = (code, signal, failure) => {
    if (done) return;
    done = true;
    if (p.errPartial && !noise(p.errPartial)) p.buf.err += p.errPartial;
    p.errPartial = '';
    clearTimeout(p.flushTimer);
    flush();
    clearTimeout(p.waitTimer);
    if (p.streamFlush) p.streamFlush();
    if (s.activity) { s.activity = null; broadcastActivity(s); }
    if (procs.get(s.id) === p) procs.delete(s.id);
    const text = failure ? `Failed to start ${h.command}: ${failure}` : p.stopped || signal ? 'Stopped' : `Exited with code ${code}`;
    if (!p.quiet) pushEvent(s, { kind: 'exit', code: failure ? -1 : code ?? null, text });
    setState(s, interactive ? 'exited' : 'idle');
    if (p.restartWith != null && sessions.has(s.id)) {
      const next = p.restartWith;
      setImmediate(() => { if (!procs.get(s.id) && startProcess(s, harness, next)) s.turns++; });
    } else if (!interactive && !rpc && s.queue && s.queue.length && !p.stopped && sessions.has(s.id)) {
      const next = s.queue.shift();
      setImmediate(() => { if (!procs.get(s.id) && startProcess(s, harness, next)) s.turns++; });
    }
  };
  child.on('error', (e) => {
    if (child.pid === undefined) finish(null, null, e.message);
    else pushEvent(s, { kind: 'err', text: `Process error: ${e.message}\n` });
  });
  child.on('close', (code, signal) => finish(code, signal, null));
  return p;
}

// Harnesses without native image input get the photos as files, referenced by path in the prompt.
function saveUploads(s, images) {
  const dir = path.join(os.tmpdir(), 'harness-remote-uploads', s.id);
  fs.mkdirSync(dir, { recursive: true });
  return images.map((img, i) => {
    const ext = img.mimeType === 'image/png' ? 'png' : img.mimeType === 'image/webp' ? 'webp' : 'jpg';
    const file = path.join(dir, `${Date.now()}-${i + 1}.${ext}`);
    fs.writeFileSync(file, Buffer.from(img.data, 'base64'));
    return file;
  });
}

function sendToSession(s, text, images = [], thumbs = [], interrupt = false) {
  const h = harnessList().find((x) => x.id === s.harness);
  if (!h) throw new Error(`Harness ${s.harness} is no longer configured`);
  const running = procs.get(s.id);
  const typed = text;
  const userEvent = (extra) => ({ kind: 'user', text: typed, ...(thumbs.length ? { thumbs } : {}), ...extra });
  if (h.mode === 'rpc') {
    const p = running || startProcess(s, h, null);
    if (!p) return;
    const busy = s.state === 'running' || s.state === 'waiting';
    const message = text || (images.length ? 'See the attached image.' : '');
    const imgs = images.length ? { images: images.map((i) => ({ type: 'image', data: i.data, mimeType: i.mimeType })) } : {};
    if (interrupt && busy && running) {
      // Stop the current step now (open questions are cancelled so the guard returns) and send right after.
      pushEvent(s, userEvent({ interrupt: true }));
      for (const askId of (p.asks ? [...p.asks.keys()] : [])) rpcWrite(p, { type: 'extension_ui_response', id: askId, cancelled: true });
      if (p.asks) p.asks.clear();
      p.afterAbort = { message, ...imgs };
      rpcWrite(p, { type: 'abort' });
      s.turns++;
      return;
    }
    pushEvent(s, userEvent(busy ? { steer: true } : {}));
    rpcWrite(p, busy ? { type: 'prompt', message, streamingBehavior: 'steer', ...imgs } : { type: 'prompt', message, ...imgs });
    if (!busy) setState(s, 'running');
    s.turns++;
    return;
  }
  if (images.length) {
    const files = saveUploads(s, images);
    text = `${text}${text ? '\n\n' : ''}${files.map((f) => `Attached image: ${f}`).join('\n')}`;
  }
  if (h.mode === 'interactive') {
    pushEvent(s, userEvent(interrupt && running ? { interrupt: true } : {}));
    let p = running;
    if (!p) p = startProcess(s, h, null);
    if (!p) return;
    if (interrupt && running && h.pty) p.child.stdin.write('\u0003');
    p.child.stdin.write(text + (h.pty ? '\r' : '\n'));
    s.turns++;
    return;
  }
  if (running && interrupt) {
    pushEvent(s, userEvent({ interrupt: true }));
    running.restartWith = text;
    running.stopped = true;
    try {
      if (IS_WIN) spawn('taskkill', ['/pid', String(running.child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on('error', () => running.child.kill());
      else process.kill(-running.child.pid, 'SIGINT');
    } catch { try { running.child.kill(); } catch {} }
    return;
  }
  if (running) {
    if (running.child.stdin) {
      pushEvent(s, userEvent({ stdin: true }));
      running.child.stdin.write(text + '\n');
      return;
    }
    // One process per message: hold it and start it when this turn ends, like Claude Code's queue.
    if (!s.queue) s.queue = [];
    s.queue.push(text);
    pushEvent(s, userEvent({ queued: true }));
    return;
  }
  pushEvent(s, userEvent({}));
  if (startProcess(s, h, text)) s.turns++;
}

function controlPath(s) {
  return path.join(path.dirname(configPath), `control-${s.id}.json`);
}

// The pi extension re-reads this before every review, so toggling the reviewer applies immediately.
function writeControl(s) {
  const provider = s.reviewProvider || s.provider;
  const base = provider === OLLAMA ? config.ollama : config.lmstudio;
  const data = { review: !!s.review, url: `${base}/v1`, model: s.reviewModel || s.model, key: provider === OLLAMA ? 'ollama' : 'lm-studio' };
  try { saveJson(controlPath(s), data, 0o600); } catch (e) { console.error('control file:', e.message); }
}

function setReview(s, enabled, model, provider) {
  s.review = !!enabled;
  if (model !== undefined) {
    s.reviewModel = model ? String(model) : '';
    s.reviewProvider = model ? String(provider || s.provider || '') : '';
  }
  writeControl(s);
  pushEvent(s, { kind: 'status', text: s.review ? `Reviewer on (${s.reviewModel || s.model || 'session model'})` : 'Reviewer off' });
  broadcastSessions();
}

function quietKill(p) {
  p.quiet = true;
  p.stopped = true;
  try {
    if (IS_WIN) spawn('taskkill', ['/pid', String(p.child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on('error', () => p.child.kill());
    else process.kill(-p.child.pid, 'SIGTERM');
  } catch { try { p.child.kill(); } catch {} }
}

function setModel(s, model, provider) {
  const h = harnessList().find((x) => x.id === s.harness);
  if (!h) throw new Error(`Harness ${s.harness} is no longer configured`);
  const providers = providersOf(h);
  if (!model) throw new Error('Pick a model');
  s.model = String(model);
  s.provider = providers.includes(provider) ? provider : s.provider || providers[0] || '';
  const via = s.provider === LMSTUDIO ? ' (LM Studio)' : s.provider === OLLAMA ? ' (Ollama)' : '';
  // A running pi only knows the models it loaded at startup, so restart it; the session id keeps the history.
  const p = procs.get(s.id);
  if (p && h.mode === 'rpc') {
    if (s.state === 'running' || s.state === 'waiting') p.restartWhenIdle = true;
    else quietKill(p);
  }
  pushEvent(s, { kind: 'status', text: `Model: ${s.model}${via}` });
  writeControl(s);
  broadcastSessions();
}

function setEffort(s, level) {
  const h = harnessList().find((x) => x.id === s.harness);
  if (!h || !h.effort) throw new Error('This harness has no effort setting');
  if (!EFFORTS.includes(level)) throw new Error(`Unknown effort level: ${level}`);
  s.thinking = level;
  const p = procs.get(s.id);
  if (p && p.rpc) rpcWrite(p, { type: 'set_thinking_level', level });
  pushEvent(s, { kind: 'status', text: `Effort: ${level}` });
  broadcastSessions();
}

function writeRaw(s, data) {
  const p = procs.get(s.id);
  if (!p || !p.child.stdin) throw new Error('Nothing is waiting for input');
  p.child.stdin.write(data);
}

function stopSession(s, signal = 'SIGINT') {
  if (s.queue && s.queue.length) {
    pushEvent(s, { kind: 'status', text: `Cleared ${s.queue.length} queued message${s.queue.length === 1 ? '' : 's'}` });
    s.queue = [];
  }
  const p = procs.get(s.id);
  if (!p) return;
  if (p.rpc && signal === 'SIGINT') {
    rpcWrite(p, { type: 'abort' });
    return;
  }
  p.stopped = true;
  const kill = (sig) => {
    if (IS_WIN) {
      spawn('taskkill', ['/pid', String(p.child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on('error', () => p.child.kill());
      return;
    }
    try { process.kill(-p.child.pid, sig); } catch { try { p.child.kill(sig); } catch {} }
  };
  kill(signal);
  setTimeout(() => { if (procs.get(s.id) === p) kill('SIGTERM'); }, 3000);
  setTimeout(() => { if (procs.get(s.id) === p) kill('SIGKILL'); }, 6000);
}

function deleteSession(s) {
  stopSession(s, 'SIGTERM');
  try { fs.unlinkSync(controlPath(s)); } catch {}
  sessions.delete(s.id);
  broadcastSessions();
  scheduleSave();
}

// ---------------------------------------------------------------- websocket (RFC 6455, server side)

class WsConn {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.fragments = [];
    this.closed = false;
    this.subs = new Set();
    this.onmessage = () => {};
    this.onclose = () => {};
    socket.setNoDelay(true);
    socket.setKeepAlive(true, 30000);
    socket.on('data', (d) => this.#receive(d));
    socket.on('close', () => this.#closed());
    socket.on('error', () => this.#closed());
    this.alive = true;
    this.pinger = setInterval(() => {
      if (!this.alive) return this.socket.destroy();
      this.alive = false;
      this.#frame(0x9, Buffer.alloc(0));
    }, 25000);
  }

  send(obj) {
    if (!this.closed) this.#frame(0x1, Buffer.from(JSON.stringify(obj)));
  }

  close(code = 1000) {
    if (this.closed) return;
    const b = Buffer.alloc(2);
    b.writeUInt16BE(code);
    this.#frame(0x8, b);
    this.socket.end();
  }

  #frame(opcode, payload) {
    const len = payload.length;
    let head;
    if (len < 126) head = Buffer.from([0x80 | opcode, len]);
    else if (len < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | opcode; head[1] = 126; head.writeUInt16BE(len, 2); }
    else { head = Buffer.alloc(10); head[0] = 0x80 | opcode; head[1] = 127; head.writeBigUInt64BE(BigInt(len), 2); }
    try { this.socket.write(Buffer.concat([head, payload])); } catch {}
  }

  #receive(data) {
    this.alive = true;
    this.buf = this.buf.length ? Buffer.concat([this.buf, data]) : data;
    while (this.buf.length >= 2) {
      const b0 = this.buf[0], b1 = this.buf[1];
      const fin = (b0 & 0x80) !== 0;
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) {
        if (this.buf.length < 10) return;
        const big = this.buf.readBigUInt64BE(2);
        if (big > BigInt(MAX_MESSAGE)) return this.close(1009);
        len = Number(big); off = 10;
      }
      if (!masked) return this.close(1002);
      if (len > MAX_MESSAGE) return this.close(1009);
      if (this.buf.length < off + 4 + len) return;
      const mask = this.buf.subarray(off, off + 4);
      const payload = Buffer.from(this.buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      this.buf = this.buf.subarray(off + 4 + len);

      if (opcode === 0x8) return this.close();
      if (opcode === 0x9) { this.#frame(0xa, payload); continue; }
      if (opcode === 0xa) continue;
      if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
        this.fragments.push(payload);
        if (this.fragments.reduce((n, f) => n + f.length, 0) > MAX_MESSAGE) return this.close(1009);
        if (!fin) continue;
        const msg = Buffer.concat(this.fragments).toString('utf8');
        this.fragments = [];
        this.onmessage(msg);
        continue;
      }
      return this.close(1002);
    }
  }

  #closed() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pinger);
    this.onclose();
  }
}

// ---------------------------------------------------------------- clients and protocol

const clients = new Set();

function broadcastEvent(s, ev) {
  for (const c of clients) if (c.subs.has(s.id)) c.send({ t: 'event', id: s.id, ev });
}

function broadcastActivity(s) {
  const a = s.activity;
  for (const c of clients) if (c.subs.has(s.id)) c.send({ t: 'activity', id: s.id, activity: a || null });
}

function broadcastProgress(s, toolId, text) {
  for (const c of clients) if (c.subs.has(s.id)) c.send({ t: 'progress', id: s.id, toolId, text });
}

let sessionsTimer = null;
function broadcastSessions() {
  if (sessionsTimer) return;
  sessionsTimer = setTimeout(() => {
    sessionsTimer = null;
    const list = sortedSummaries();
    for (const c of clients) c.send({ t: 'sessions', sessions: list });
  }, 50);
}

function sortedSummaries() {
  return [...sessions.values()].sort((a, b) => b.updatedAt - a.updatedAt).map(summary);
}

function hello() {
  return {
    t: 'hello', protocol: PROTOCOL, version: VERSION, host: os.hostname(), platform: process.platform, now: Date.now(),
    roots: realRoots(), harnesses: harnessList().map(publicHarness), sessions: sortedSummaries(),
  };
}

async function handle(c, raw) {
  let msg;
  try { msg = JSON.parse(raw); } catch { return c.send({ t: 'error', error: 'Bad JSON' }); }
  const reply = (obj) => c.send({ ...obj, rid: msg.rid });
  const need = (id) => {
    const s = sessions.get(id);
    if (!s) throw new Error('No such session');
    return s;
  };
  try {
    switch (msg.t) {
      case 'hello': return reply(hello());
      case 'list': return reply({ t: 'sessions', sessions: sortedSummaries() });
      case 'models': return reply({ t: 'models', ...(await listModels()) });
      case 'lms-start': {
        const output = await startLmStudio();
        return reply({ t: 'models', output, ...(await listModels()) });
      }
      case 'ls': return reply({ t: 'ls', ...listDir(msg.path) });
      case 'create': {
        const s = createSession(msg);
        c.subs.add(s.id);
        return reply({ t: 'created', session: summary(s) });
      }
      case 'subscribe': {
        const s = need(msg.id);
        c.subs.add(s.id);
        const after = Number(msg.after) || 0;
        return reply({ t: 'history', id: s.id, session: summary(s), events: s.events.filter((e) => e.seq > after), activity: s.activity || null });
      }
      case 'unsubscribe': c.subs.delete(msg.id); return reply({ t: 'ok' });
      case 'send': {
        const text = String(msg.text ?? '');
        const images = (Array.isArray(msg.images) ? msg.images : []).slice(0, MAX_IMAGES)
          .filter((i) => i && typeof i.data === 'string' && /^image\/(jpeg|png|webp)$/.test(i.mimeType));
        const thumbs = (Array.isArray(msg.thumbs) ? msg.thumbs : []).slice(0, MAX_IMAGES).filter((t) => typeof t === 'string' && t.length < 40000);
        if (!text.trim() && !images.length) throw new Error('Empty message');
        if (Buffer.byteLength(text) > MAX_PROMPT) throw new Error('Message is too long (100 KB max)');
        sendToSession(need(msg.id), text, images, thumbs, msg.interrupt === true);
        return reply({ t: 'ok' });
      }
      case 'set-model': setModel(need(msg.id), msg.model, msg.provider); return reply({ t: 'ok' });
      case 'set-review': setReview(need(msg.id), msg.enabled !== false, msg.model, msg.provider); return reply({ t: 'ok' });
      case 'set-effort': setEffort(need(msg.id), String(msg.level)); return reply({ t: 'ok' });
      case 'stdin': writeRaw(need(msg.id), String(msg.data ?? '')); return reply({ t: 'ok' });
      case 'answer': answerAsk(need(msg.id), String(msg.askId), msg); return reply({ t: 'ok' });
      case 'stop': stopSession(need(msg.id), msg.signal === 'SIGTERM' ? 'SIGTERM' : 'SIGINT'); return reply({ t: 'ok' });
      case 'restart': {
        const s = need(msg.id);
        const h = harnessList().find((x) => x.id === s.harness);
        if (!h || h.mode !== 'interactive') throw new Error('Only interactive sessions can be restarted');
        if (!procs.get(s.id)) startProcess(s, h, null);
        return reply({ t: 'ok' });
      }
      case 'rename': { const s = need(msg.id); s.title = String(msg.title || s.title).slice(0, 120); broadcastSessions(); scheduleSave(); return reply({ t: 'ok' }); }
      case 'delete': deleteSession(need(msg.id)); return reply({ t: 'ok' });
      case 'ping': return reply({ t: 'pong' });
      default: throw new Error(`Unknown message type: ${msg.t}`);
    }
  } catch (e) {
    reply({ t: 'error', error: String(e.message || e) });
  }
}

// ---------------------------------------------------------------- pairing

const pairing = {
  code: String(crypto.randomInt(0, 1000000)).padStart(6, '0'),
  expires: Date.now() + 10 * 60 * 1000,
  failures: 0,
};

function tokenOk(given) {
  if (typeof given !== 'string') return false;
  const a = crypto.createHash('sha256').update(given).digest();
  const b = crypto.createHash('sha256').update(config.token).digest();
  return crypto.timingSafeEqual(a, b);
}

function readBody(req, limit = 4096) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (d) => { size += d.length; if (size > limit) { reject(new Error('too large')); req.destroy(); } else chunks.push(d); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function json(res, status, obj) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(obj));
}

// ---------------------------------------------------------------- http server

async function onRequest(req, res) {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'GET' && url.pathname === '/health') {
    return json(res, 200, { ok: true, name: 'harness-bridge', version: VERSION, protocol: PROTOCOL, host: os.hostname() });
  }
  if (req.method === 'POST' && url.pathname === '/pair') {
    let body = {};
    try { body = JSON.parse(await readBody(req)); } catch {}
    if (pairing.failures >= 10 || Date.now() > pairing.expires) {
      return json(res, 410, { error: 'Pairing code expired. Restart the bridge to get a new one.' });
    }
    const given = String(body.code || '').replace(/\D/g, '');
    if (given.length === 6 && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(pairing.code))) {
      pairing.expires = 0;
      console.log(`Paired a device (${body.device || 'unknown'}) from ${req.socket.remoteAddress}`);
      return json(res, 200, { token: config.token, host: os.hostname() });
    }
    pairing.failures++;
    await new Promise((r) => setTimeout(r, 750));
    return json(res, 401, { error: 'Wrong pairing code' });
  }
  json(res, 404, { error: 'Not found' });
}

function onUpgrade(req, socket) {
  const url = new URL(req.url, 'http://x');
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : url.searchParams.get('token');
  const key = req.headers['sec-websocket-key'];
  if (url.pathname !== '/ws' || !key || (req.headers.upgrade || '').toLowerCase() !== 'websocket') {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
    return;
  }
  if (!tokenOk(token)) {
    socket.end('HTTP/1.1 401 Unauthorized\r\n\r\n');
    return;
  }
  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  const c = new WsConn(socket);
  clients.add(c);
  c.onmessage = (m) => { handle(c, m); };
  c.onclose = () => clients.delete(c);
  c.send(hello());
}

const tls = config.tls && config.tls.cert && config.tls.key
  ? { cert: fs.readFileSync(config.tls.cert), key: fs.readFileSync(config.tls.key) }
  : null;
const server = tls ? https.createServer(tls, onRequest) : http.createServer(onRequest);
server.on('upgrade', onUpgrade);

server.listen(port, host, () => {
  const scheme = tls ? 'https' : 'http';
  const addrs = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.internal || a.family !== 'IPv4') continue;
      addrs.push({ name, address: a.address, tailscale: a.address.startsWith('100.') || /tailscale|utun/i.test(name) });
    }
  }
  const harnesses = harnessList().map(publicHarness);
  console.log(`\nHarness Bridge ${VERSION} listening on ${scheme}://${host}:${port}`);
  console.log(`Config: ${configPath}`);
  console.log(`Allowed roots: ${realRoots().join(', ')}`);
  console.log('Harnesses:');
  for (const h of harnesses) console.log(`  ${h.available ? '✓' : '✗'} ${h.name}${h.available ? '' : `  (${h.reason})`}`);
  if (!harnesses.some((h) => h.available)) console.log('  None of the harnesses are usable yet. Install one (e.g. `pip install aider-chat` or `npm i -g opencode-ai`) and restart.');
  if (host === '0.0.0.0' || host === '::') {
    console.log('\nReachable at:');
    for (const a of addrs) console.log(`  ${scheme}://${a.address}:${port}  (${a.name}${a.tailscale ? ', Tailscale' : ''})`);
  }
  console.log(`\nPairing code: ${pairing.code}   (valid 10 minutes, one use)`);
  console.log('Open Harness Remote on your phone → Add computer → enter the address and this code.\n');
  if (!tls) console.log('Traffic is not encrypted. Use Tailscale/WireGuard or set "tls" in the config when off your home network.\n');
  if (IS_WIN) console.log('Windows: if the phone cannot connect, allow Node.js through Windows Defender Firewall for Private networks and make sure your Wi-Fi is set to Private.\n');
  console.log('Leave this window open while you use the app. Press Ctrl+C to stop.\n');
});

server.on('error', async (e) => {
  if (e.code === 'EADDRINUSE') {
    const other = await fetchJson(`http://127.0.0.1:${port}/health`).catch(() => null);
    if (other && other.name === 'harness-bridge') {
      console.error(`A Harness Bridge (${other.version}) is already running on port ${port}, so you don't need a second one.`);
      console.error('Use the window it is running in. For a new pairing code, stop that one with Ctrl+C and start it again.');
      if (other.version !== VERSION) console.error(`Note: that one is version ${other.version}; this file is ${VERSION}. Restart it to pick up the update.`);
    } else {
      console.error(`Port ${port} is already used by another program${port === 8787 ? ' (Cloudflare\'s "wrangler dev" uses 8787 by default)' : ''}.`);
      console.error(IS_WIN
        ? `  See which program:  Get-Process -Id (Get-NetTCPConnection -LocalPort ${port} -State Listen).OwningProcess`
        : `  See which program:  lsof -iTCP:${port} -sTCP:LISTEN`);
      console.error(`  Or use another port:  node harness-bridge.mjs --port ${port + 1}   and put :${port + 1} after the address on your phone.`);
      console.error(`  To keep that port, set "port": ${port + 1} in ${configPath}`);
    }
  } else if (e.code === 'EACCES') console.error(`Not allowed to listen on port ${port}. Try --port 8787 or higher.`);
  else console.error(`Server error: ${e.message}`);
  process.exit(1);
});

function shutdown() {
  for (const s of sessions.values()) stopSession(s, 'SIGTERM');
  clearTimeout(saveTimer);
  saveTimer = null;
  const data = [...sessions.values()].map(({ id, title, harness, cwd, model, provider, thinking, permissions, review, reviewModel, reviewProvider, createdAt, updatedAt, turns, state, seq, events }) => ({ id, title, harness, cwd, model, provider, thinking, permissions, review, reviewModel, reviewProvider, createdAt, updatedAt, turns, state: state === 'running' || state === 'waiting' ? 'idle' : state, seq, events }));
  try { saveJson(sessionsPath, data, 0o600); } catch {}
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

