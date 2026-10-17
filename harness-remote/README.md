# Harness Remote

Control your agent harnesses (Aider, OpenCode, Goose, Codex, Claude Code, or any CLI) from an Android phone, running against **your own local models** (Ollama / LM Studio). There's no cloud relay and no account. Your phone talks straight to your computer over your LAN or Tailscale.

```
 Android phone                          Your computer
┌──────────────────┐   WebSocket      ┌───────────────────────────────┐
│ Harness Remote   │◄────────────────►│ harness-bridge.mjs (Node)     │
│  • sessions      │  token auth      │   spawns harness CLIs ──────► │ aider / opencode / goose /
│  • live output   │  LAN/Tailscale   │   streams stdout/stderr       │ codex --oss / claude / …
│  • y/n/Enter     │                  │   lists Ollama + LM Studio    │        │
│  • notifications │                  │   models                      │        ▼
└──────────────────┘                  └───────────────────────────────┘   Ollama :11434
                                                                          LM Studio :1234
```

## Why this exists

As of October 2026, the closest existing options each miss one or more of *Android*, *any harness*, *local models* or *no third-party relay*:

| App | Harnesses | Android | Relay | Notes |
|---|---|---|---|---|
| Claude Code Remote Control | Claude Code only | yes | Anthropic relay | Claude models only |
| Happy (slopus/happy) | Claude Code, Codex, a few others | yes | Happy server (self-hostable) | MIT, no local-model docs |
| OpenCode Android clients (OpenCode Mobile, P4OC, AndCode…) | OpenCode only | yes | direct to `opencode serve` | Good choice if you only use OpenCode |
| PhoneCMD | many CLIs | yes | direct | closed source |
| AgentsRoom / CodeAgent Mobile | many CLIs | yes | vendor relay | commercial |
| Ciao, Grass, Cursor Mobile | varies | no (iOS) | varies | |

Harness Remote is a small open option: one dependency-free Node script on the computer and a native Kotlin/Compose app on the phone.

## Quick start

### 1. Computer (Linux or macOS; Windows via WSL)

Requires Node.js 18+ and at least one harness on your `PATH`.

```bash
node harness-remote/bridge/harness-bridge.mjs
```

It prints something like:

```
Harness Bridge 0.1.0 listening on http://0.0.0.0:8787
Harnesses: Aider, OpenCode, Goose (not installed), Codex (local OSS), Claude Code (Ollama), Ollama chat
Reachable at:
  http://192.168.1.20:8787  (wlan0)
  http://100.88.12.4:8787   (tailscale0, Tailscale)

Pairing code: 482913   (valid 10 minutes, one use)
```

### 2. Phone

Install the APK (`android/app/build/outputs/apk/debug/app-debug.apk` after building, see below). Then:

1. Tap **Add computer**, enter `192.168.1.20` (or the Tailscale `100.x` address) and the 6-digit code.
2. Tap **New session**, pick a harness, a local model (listed live from Ollama / LM Studio) and a project folder.
3. Type a message. Output streams in. When the harness asks something (`[y/n]`, `?`, a prompt), the session shows **Needs input** and quick-reply keys appear.

Leave the app and it keeps the connection in a foreground service. It notifies you when a turn finishes or a harness is waiting for you. Tap the notification to jump back into that session.

## Building the APK

```bash
cd harness-remote/android
./gradlew assembleDebug          # needs JDK 17+ and the Android SDK (ANDROID_HOME or local.properties)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open `harness-remote/android` in Android Studio and press Run. `./gradlew assembleRelease` produces a minified build, signed with the debug key so it installs as-is. Use your own keystore before publishing anywhere.

## How sessions work

Each harness has a **mode**:

- **turn** (Aider, OpenCode, Goose, Codex, Claude Code). Every message runs the harness once, non-interactively, in the session's folder. Later messages use the harness's own *continue/resume* flag, so the conversation carries on. stdin is closed by default, because several of these CLIs read stdin to EOF and would hang on an open pipe.
- **interactive** (Ollama chat, Shell). One long-lived process runs inside a pseudo-terminal (via `script`). Messages and keys (Enter, Esc, Tab, arrows, Ctrl-C, Ctrl-D) go to it as keystrokes.

Transcripts are kept by the bridge in `~/.harness-remote/sessions.json`, so you can close the app, reconnect from anywhere and pick up where you left off.

### Built-in presets

| id | Runs | Model is passed as |
|---|---|---|
| `aider` | `aider --message …` (+ `--restore-chat-history` after the first turn) | `--model ollama_chat/<model>`, `OLLAMA_API_BASE` |
| `opencode` | `opencode run …` (+ `--continue`) | `--model ollama/<model>`. Needs an `ollama` provider in `opencode.json` ([docs](https://opencode.ai/docs/providers/)) |
| `goose` | `goose run --name harness-remote-<id> --text …` (+ `--resume`) | `GOOSE_PROVIDER=ollama GOOSE_MODEL=<model>` |
| `codex` | `codex exec --oss --local-provider ollama --sandbox workspace-write …` (+ `resume --last`) | `-m <model>` |
| `claude-ollama` | `claude -p --permission-mode acceptEdits …` (+ `--continue`) | `--model <model>`, `ANTHROPIC_BASE_URL=<ollama>` (Ollama's Anthropic-compatible API) |
| `ollama-chat` | `ollama run <model>` in a PTY | positional |
| `shell` | your login shell in a PTY | n/a. **Disabled by default** |

Presets whose command isn't installed show up greyed out in the app. Local models usually need a bigger context window than Ollama's default for agent work. Set `OLLAMA_CONTEXT_LENGTH` (for example 32768) on the Ollama server.

## Configuration

`~/.harness-remote/config.json` is created on first run:

```jsonc
{
  "port": 8787,
  "host": "0.0.0.0",                 // "127.0.0.1" + Tailscale serve, or a Tailscale IP, to restrict
  "token": "…",                      // generated; rotate with --rotate-token (unpairs all phones)
  "roots": ["/home/you"],            // the phone can only pick folders inside these
  "ollama": "http://127.0.0.1:11434",
  "lmstudio": "http://127.0.0.1:1234",
  "tls": { "cert": "/path/cert.pem", "key": "/path/key.pem" },   // optional, e.g. from `tailscale cert`
  "harnesses": [
    // override a preset…
    { "id": "aider", "args": ["{modelArgs}", "--yes-always", "--no-pretty", "--no-auto-commits", "--message", "{prompt}"] },
    // …enable a disabled one…
    { "id": "shell", "disabled": false },
    // …or add any CLI
    {
      "id": "my-agent",
      "name": "My agent",
      "mode": "turn",
      "command": "my-agent",
      "args": ["--model", "{model}", "--prompt", "{prompt}"],
      "continueArgs": ["--resume", "--model", "{model}", "--prompt", "{prompt}"],
      "env": { "OPENAI_BASE_URL": "{lmstudio}/v1" },
      "models": "lmstudio"
    }
  ]
}
```

Placeholders: `{prompt}` `{model}` `{session}` `{cwd}` `{ollama}` `{lmstudio}`. An argument that is exactly `{modelArgs}` expands to the harness's `modelArgs` when a model is picked, and to nothing otherwise. `modelEnv` is applied only when a model is picked. Arguments go straight to the process with no shell in between, so nothing you type on the phone is shell-interpreted. Other fields: `pty: true`, `stdin: "pipe"`, `requiresModel: true`.

Check what the bridge will offer with `node harness-bridge.mjs --print-config`.

## Security

The bridge runs programs on your computer for whoever holds the token, so treat the token like an SSH key.

- **Pairing**: a 6-digit, one-use code, valid for 10 minutes after the bridge starts. Pairing locks after 10 wrong attempts. The phone receives the 32-character random token, and every WebSocket connection is checked against it in constant time.
- **No arbitrary commands**: the phone can only start harnesses listed in your config, in folders under `roots`. The `shell` preset, which would give full access, is off unless you enable it.
- **Transport**: plain `ws://` by default. That's fine on a trusted LAN. Anywhere else, use **Tailscale** (WireGuard-encrypted), or set `tls` and the phone uses `https://`/`wss://` (enter the address with `https://`).
- The config and transcripts are written with mode `0600`.

## Protocol

JSON over a WebSocket at `/ws` (`Authorization: Bearer <token>`). Requests carry a `rid` that the reply echoes.

| Client → bridge | Reply |
|---|---|
| `hello` | `{t:"hello", host, version, roots, harnesses[], sessions[]}` (also sent on connect) |
| `models` | `{t:"models", ollama[], lmstudio[]}` |
| `ls {path}` | `{t:"ls", path, parent, dirs[], git}` |
| `create {harness, cwd, model, title}` | `{t:"created", session}` |
| `subscribe {id, after}` | `{t:"history", events[]}`, then live `{t:"event", id, ev}` |
| `send {id, text}` · `stdin {id, data}` · `stop {id}` · `restart {id}` · `rename {id, title}` · `delete {id}` | `{t:"ok"}` or `{t:"error", error}` |

The bridge also pushes `{t:"sessions", sessions[]}` whenever any session changes state (`idle` / `running` / `waiting` / `exited`). HTTP: `GET /health`, `POST /pair {code}`.

## Limitations

- Output is shown as cleaned text (ANSI stripped, `\r` progress lines collapsed), not as a full terminal emulator. Full-screen TUIs in interactive mode are usable but not pretty. The per-message presets are the intended way to work.
- "Needs input" is a heuristic: output went quiet for 1.5 s and the last line looks like a prompt.
- OpenCode's `--continue` and Codex's `resume --last` pick the most recent session *in that folder*. Two phone sessions on the same folder with the same harness will share history.
- Native Windows isn't supported yet (npm `.cmd` shims need a shell). Use WSL.
