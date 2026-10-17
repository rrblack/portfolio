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

const VERSION = '0.1.0';
const PROTOCOL = 1;
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE = 1024 * 1024;
const MAX_PROMPT = 100 * 1024;
const MAX_EVENTS = 4000;
const FLUSH_MS = 80;
const WAITING_MS = 1500;
const WAITING_RE = /(\?|\[y\/n\]|\(y\/n\)|\(yes\/no\)|:|>|›|❯|\$|#)\s*$/i;

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
config.port ??= 8787;
config.host ??= '0.0.0.0';
config.roots ??= [os.homedir()];
config.ollama ??= process.env.OLLAMA_HOST ? normalizeUrl(process.env.OLLAMA_HOST) : 'http://127.0.0.1:11434';
config.lmstudio ??= 'http://127.0.0.1:1234';
config.harnesses ??= [];
if (configDirty) saveJson(configPath, config, 0o600);

const port = Number(flag('--port') || config.port);
const host = String(flag('--host') || config.host);

function normalizeUrl(u) {
  return /^https?:\/\//.test(u) ? u.replace(/\/$/, '') : `http://${u.replace(/\/$/, '')}`;
}

// ---------------------------------------------------------------- harness presets

// Placeholders: {prompt} {model} {session} {cwd} {ollama} {lmstudio}
// "{modelArgs}" as a whole arg is replaced by modelArgs when a model is chosen, removed otherwise.
// mode "turn": one process per message, stdin closed unless stdin: 'pipe'. mode "interactive": one long-lived process, messages go to stdin.
const PRESETS = [
  {
    id: 'aider',
    name: 'Aider',
    description: 'Aider pair programmer, one message per run, chat history restored between runs.',
    mode: 'turn',
    command: 'aider',
    args: ['{modelArgs}', '--yes-always', '--no-pretty', '--no-fancy-input', '--no-check-update', '--no-show-release-notes', '--no-show-model-warnings', '--message', '{prompt}'],
    continueArgs: ['{modelArgs}', '--yes-always', '--no-pretty', '--no-fancy-input', '--no-check-update', '--no-show-release-notes', '--no-show-model-warnings', '--restore-chat-history', '--message', '{prompt}'],
    modelArgs: ['--model', 'ollama_chat/{model}'],
    env: { OLLAMA_API_BASE: '{ollama}' },
    models: 'ollama',
  },
  {
    id: 'opencode',
    name: 'OpenCode',
    description: 'opencode run, continuing the last session in the working directory. Needs an "ollama" provider in opencode.json.',
    mode: 'turn',
    command: 'opencode',
    args: ['run', '{modelArgs}', '{prompt}'],
    continueArgs: ['run', '--continue', '{modelArgs}', '{prompt}'],
    modelArgs: ['--model', 'ollama/{model}'],
    models: 'ollama',
  },
  {
    id: 'goose',
    name: 'Goose',
    description: 'goose run with a named session per phone session.',
    mode: 'turn',
    command: 'goose',
    args: ['run', '--name', 'harness-remote-{session}', '--text', '{prompt}'],
    continueArgs: ['run', '--name', 'harness-remote-{session}', '--resume', '--text', '{prompt}'],
    modelEnv: { GOOSE_PROVIDER: 'ollama', GOOSE_MODEL: '{model}', OLLAMA_HOST: '{ollama}' },
    models: 'ollama',
  },
  {
    id: 'codex',
    name: 'Codex (local OSS)',
    description: 'codex exec --oss against a local Ollama model.',
    mode: 'turn',
    command: 'codex',
    args: ['exec', '--oss', '--local-provider', 'ollama', '--sandbox', 'workspace-write', '--skip-git-repo-check', '{modelArgs}', '{prompt}'],
    continueArgs: ['exec', '--oss', '--local-provider', 'ollama', '--sandbox', 'workspace-write', '--skip-git-repo-check', '{modelArgs}', 'resume', '--last', '{prompt}'],
    modelArgs: ['-m', '{model}'],
    models: 'ollama',
  },
  {
    id: 'claude-ollama',
    name: 'Claude Code (Ollama)',
    description: 'claude -p pointed at Ollama\'s Anthropic-compatible endpoint.',
    mode: 'turn',
    command: 'claude',
    args: ['-p', '--permission-mode', 'acceptEdits', '{modelArgs}', '{prompt}'],
    continueArgs: ['-p', '--continue', '--permission-mode', 'acceptEdits', '{modelArgs}', '{prompt}'],
    modelArgs: ['--model', '{model}'],
    env: { ANTHROPIC_BASE_URL: '{ollama}', ANTHROPIC_AUTH_TOKEN: 'ollama', ANTHROPIC_API_KEY: '' },
    modelEnv: { ANTHROPIC_DEFAULT_HAIKU_MODEL: '{model}' },
    models: 'ollama',
  },
  {
    id: 'ollama-chat',
    name: 'Ollama chat',
    description: 'Plain interactive ollama run session (no tools).',
    mode: 'interactive',
    pty: true,
    command: 'ollama',
    args: ['run', '{model}'],
    models: 'ollama',
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

function harnessList() {
  const byId = new Map(PRESETS.map((h) => [h.id, { ...h, preset: true }]));
  for (const h of config.harnesses) {
    if (!h || !h.id) continue;
    byId.set(h.id, { ...(byId.get(h.id) || {}), ...h });
  }
  return [...byId.values()].filter((h) => !h.disabled && h.command);
}

const whichCache = new Map();
function which(cmd) {
  if (whichCache.has(cmd)) return whichCache.get(cmd);
  let found = null;
  if (cmd.includes('/')) {
    try { fs.accessSync(cmd, fs.constants.X_OK); found = cmd; } catch {}
  } else {
    for (const dir of (process.env.PATH || '').split(path.delimiter)) {
      const p = path.join(dir, cmd);
      try { fs.accessSync(p, fs.constants.X_OK); if (fs.statSync(p).isFile()) { found = p; break; } } catch {}
    }
  }
  whichCache.set(cmd, found);
  return found;
}

function publicHarness(h) {
  return {
    id: h.id,
    name: h.name || h.id,
    description: h.description || '',
    mode: h.mode === 'interactive' ? 'interactive' : 'turn',
    models: h.models || null,
    requiresModel: !!h.requiresModel,
    available: !!which(h.command),
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
  env.NO_COLOR ??= '1';
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

function realRoots() {
  return config.roots.map((r) => { try { return fs.realpathSync(r.replace(/^~(?=$|\/)/, os.homedir())); } catch { return null; } }).filter(Boolean);
}

function insideRoots(p) {
  let real;
  try { real = fs.realpathSync(p); } catch { return null; }
  for (const root of realRoots()) {
    if (real === root || real.startsWith(root.endsWith(path.sep) ? root : root + path.sep)) return real;
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
  const isRoot = realRoots().includes(dir);
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

async function listModels() {
  const result = { ollama: [], lmstudio: [], errors: {} };
  await Promise.all([
    fetchJson(`${config.ollama}/api/tags`).then((j) => {
      result.ollama = (j.models || []).map((m) => ({ name: m.name, size: m.size || 0, family: m.details?.family || '', params: m.details?.parameter_size || '' }));
    }).catch((e) => { result.errors.ollama = String(e.message || e); }),
    fetchJson(`${config.lmstudio}/v1/models`).then((j) => {
      result.lmstudio = (j.data || []).map((m) => ({ name: m.id, size: 0, family: '', params: '' }));
    }).catch((e) => { result.errors.lmstudio = String(e.message || e); }),
  ]);
  return result;
}

// ---------------------------------------------------------------- sessions

const sessions = new Map();
for (const s of loadJson(sessionsPath, [])) {
  if (!s || !s.id) continue;
  s.state = s.state === 'running' || s.state === 'waiting' ? 'idle' : s.state || 'idle';
  s.events ||= [];
  s.seq ||= s.events.length ? s.events[s.events.length - 1].seq : 0;
  sessions.set(s.id, s);
}
const procs = new Map();

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const data = [...sessions.values()].map(({ id, title, harness, cwd, model, createdAt, updatedAt, turns, state, seq, events }) => ({ id, title, harness, cwd, model, createdAt, updatedAt, turns, state, seq, events }));
    try { saveJson(sessionsPath, data, 0o600); } catch (e) { console.error('save failed:', e.message); }
  }, 1000);
}

function summary(s) {
  const last = [...s.events].reverse().find((e) => e.kind === 'user' || e.kind === 'out');
  return {
    id: s.id, title: s.title, harness: s.harness, cwd: s.cwd, model: s.model,
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
  s.state = state;
  s.updatedAt = Date.now();
  broadcastSessions();
  scheduleSave();
}

function createSession({ harness, cwd, model, title }) {
  const h = harnessList().find((x) => x.id === harness);
  if (!h) throw new Error(`Unknown harness: ${harness}`);
  if (!which(h.command)) throw new Error(`${h.command} is not installed or not on PATH`);
  if (h.requiresModel && !model) throw new Error(`${h.name || h.id} needs a model`);
  const dir = insideRoots(cwd || realRoots()[0] || os.homedir());
  if (!dir || !safeIsDir(dir)) throw new Error('Working directory is missing or outside the allowed roots');
  const id = crypto.randomBytes(6).toString('hex');
  const now = Date.now();
  const s = {
    id, harness: h.id, cwd: dir, model: model || '', turns: 0, state: 'idle', seq: 0, events: [],
    title: (title || '').trim() || `${h.name || h.id} · ${path.basename(dir)}`,
    createdAt: now, updatedAt: now,
  };
  sessions.set(id, s);
  pushEvent(s, { kind: 'status', text: `Session created: ${h.name || h.id} in ${dir}${model ? ` with ${model}` : ''}` });
  if (h.mode === 'interactive') startProcess(s, h, null);
  broadcastSessions();
  return s;
}

function startProcess(s, h, prompt) {
  const vars = { prompt: prompt ?? '', model: s.model, session: s.id, cwd: s.cwd, ollama: config.ollama, lmstudio: config.lmstudio };
  const template = h.mode === 'turn' && s.turns > 0 && h.continueArgs ? h.continueArgs : h.args || [];
  let cmd = which(h.command) || h.command;
  let args = buildArgs(template, h, vars);
  if (h.pty) [cmd, args] = wrapPty(cmd, args);
  const interactive = h.mode === 'interactive';
  const stdinMode = h.stdin || (interactive || h.pty ? 'pipe' : 'ignore');
  const env = buildEnv(h, vars);
  if (h.pty) env.TERM = env.TERM && env.TERM !== 'dumb' ? env.TERM : 'xterm-256color';

  let child;
  try {
    child = spawn(cmd, args, { cwd: s.cwd, env, stdio: [stdinMode, 'pipe', 'pipe'], detached: process.platform !== 'win32' });
  } catch (e) {
    pushEvent(s, { kind: 'err', text: `Failed to start ${h.command}: ${e.message}\n` });
    setState(s, 'idle');
    return null;
  }

  const p = { child, harness: h, buf: { out: '', err: '' }, flushTimer: null, waitTimer: null, tail: '' };
  procs.set(s.id, p);
  setState(s, 'running');

  const flush = () => {
    p.flushTimer = null;
    for (const kind of ['out', 'err']) {
      if (!p.buf[kind]) continue;
      const text = p.buf[kind];
      p.buf[kind] = '';
      pushEvent(s, { kind, text });
      p.tail = (p.tail + stripAnsi(text)).slice(-400);
    }
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
  child.stdout.on('data', onData('out'));
  child.stderr.on('data', onData('err'));
  child.stdin?.on('error', () => {});
  let done = false;
  const finish = (code, signal, failure) => {
    if (done) return;
    done = true;
    clearTimeout(p.flushTimer);
    flush();
    clearTimeout(p.waitTimer);
    if (procs.get(s.id) === p) procs.delete(s.id);
    const text = failure ? `Failed to start ${h.command}: ${failure}` : signal ? `Stopped (${signal})` : `Exited with code ${code}`;
    pushEvent(s, { kind: 'exit', code: failure ? -1 : code ?? null, text });
    setState(s, interactive ? 'exited' : 'idle');
  };
  child.on('error', (e) => {
    if (child.pid === undefined) finish(null, null, e.message);
    else pushEvent(s, { kind: 'err', text: `Process error: ${e.message}\n` });
  });
  child.on('close', (code, signal) => finish(code, signal, null));
  return p;
}

function sendToSession(s, text) {
  const h = harnessList().find((x) => x.id === s.harness);
  if (!h) throw new Error(`Harness ${s.harness} is no longer configured`);
  const running = procs.get(s.id);
  if (h.mode === 'interactive') {
    pushEvent(s, { kind: 'user', text });
    let p = running;
    if (!p) p = startProcess(s, h, null);
    if (!p) return;
    p.child.stdin.write(text + (h.pty ? '\r' : '\n'));
    s.turns++;
    return;
  }
  if (running) {
    if (running.child.stdin) {
      pushEvent(s, { kind: 'user', text, stdin: true });
      running.child.stdin.write(text + '\n');
      return;
    }
    throw new Error('A turn is still running. Stop it first or wait for it to finish.');
  }
  pushEvent(s, { kind: 'user', text });
  if (startProcess(s, h, text)) s.turns++;
}

function writeRaw(s, data) {
  const p = procs.get(s.id);
  if (!p || !p.child.stdin) throw new Error('Nothing is waiting for input');
  p.child.stdin.write(data);
}

function stopSession(s, signal = 'SIGINT') {
  const p = procs.get(s.id);
  if (!p) return;
  const kill = (sig) => {
    try {
      if (process.platform !== 'win32') process.kill(-p.child.pid, sig);
      else p.child.kill(sig);
    } catch { try { p.child.kill(sig); } catch {} }
  };
  kill(signal);
  setTimeout(() => { if (procs.get(s.id) === p) kill('SIGTERM'); }, 3000);
  setTimeout(() => { if (procs.get(s.id) === p) kill('SIGKILL'); }, 6000);
}

function deleteSession(s) {
  stopSession(s, 'SIGTERM');
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
    t: 'hello', protocol: PROTOCOL, version: VERSION, host: os.hostname(), platform: process.platform,
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
        return reply({ t: 'history', id: s.id, session: summary(s), events: s.events.filter((e) => e.seq > after) });
      }
      case 'unsubscribe': c.subs.delete(msg.id); return reply({ t: 'ok' });
      case 'send': {
        const text = String(msg.text ?? '');
        if (!text.trim()) throw new Error('Empty message');
        if (Buffer.byteLength(text) > MAX_PROMPT) throw new Error('Message is too long (100 KB max)');
        sendToSession(need(msg.id), text);
        return reply({ t: 'ok' });
      }
      case 'stdin': writeRaw(need(msg.id), String(msg.data ?? '')); return reply({ t: 'ok' });
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
  console.log(`Harnesses: ${harnesses.map((h) => `${h.name}${h.available ? '' : ' (not installed)'}`).join(', ')}`);
  if (host === '0.0.0.0' || host === '::') {
    console.log('\nReachable at:');
    for (const a of addrs) console.log(`  ${scheme}://${a.address}:${port}  (${a.name}${a.tailscale ? ', Tailscale' : ''})`);
  }
  console.log(`\nPairing code: ${pairing.code}   (valid 10 minutes, one use)`);
  console.log('Open Harness Remote on your phone → Add computer → enter the address and this code.\n');
  if (!tls) console.log('Traffic is not encrypted. Use Tailscale/WireGuard or set "tls" in the config when off your home network.\n');
});

server.on('error', (e) => {
  console.error(`Server error: ${e.message}`);
  process.exit(1);
});

function shutdown() {
  for (const s of sessions.values()) stopSession(s, 'SIGTERM');
  clearTimeout(saveTimer);
  saveTimer = null;
  const data = [...sessions.values()].map(({ id, title, harness, cwd, model, createdAt, updatedAt, turns, state, seq, events }) => ({ id, title, harness, cwd, model, createdAt, updatedAt, turns, state: state === 'running' || state === 'waiting' ? 'idle' : state, seq, events }));
  try { saveJson(sessionsPath, data, 0o600); } catch {}
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

