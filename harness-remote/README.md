# Harness Remote

Control your agent harnesses (Pi, Aider, OpenCode, Codex, Goose, Claude Code, or any CLI) from an Android phone, running against **your own local models** in LM Studio or Ollama. There's no cloud relay and no account. Your phone talks straight to your computer over your LAN or Tailscale.

```
 Android phone                          Your computer
┌──────────────────┐   WebSocket      ┌───────────────────────────────┐
│ Harness Remote   │◄────────────────►│ harness-bridge.mjs (Node)     │
│  • sessions      │  token auth      │   spawns harness CLIs ──────► │ pi / aider / opencode / codex
│  • live output   │  LAN/Tailscale   │   streams stdout/stderr       │ --oss / goose / claude / …
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
| LM Studio Bionic | its own agent | no (desktop only) | n/a | No remote control; Harness Remote uses the same LM Studio models through pi and the other harnesses instead |

Harness Remote is a small open option: one dependency-free Node script on the computer and a native Kotlin/Compose app on the phone.

## Quick start

### 1. Computer (Windows, macOS or Linux)

Requires Node.js 18+ ([nodejs.org](https://nodejs.org), LTS) and at least one harness on your `PATH`.

- **Windows:** double-click `bridge/start-bridge.cmd`, or open a terminal in the folder that contains `harness-bridge.mjs` and run `node harness-bridge.mjs`.
- **macOS / Linux:** `node harness-remote/bridge/harness-bridge.mjs`

Leave the window open while you use the app. The bridge is a server: after the startup text it just waits for your phone.

It prints something like:

```
Harness Bridge 0.2.0 listening on http://0.0.0.0:8787
Harnesses:
  ✓ Pi
  ✓ Aider
  ✗ OpenCode  (opencode was not found on PATH)
Reachable at:
  http://192.168.1.20:8787  (wlan0)
  http://100.88.12.4:8787   (tailscale0, Tailscale)

Pairing code: 482913   (valid 10 minutes, one use)
```

### 2. Phone

Install the APK (`android/app/build/outputs/apk/debug/app-debug.apk` after building, see below). Then:

1. Tap **Add computer**, enter `192.168.1.20` (or the Tailscale `100.x` address) and the 6-digit code.
2. Tap **New session**, pick a harness, a local model (listed live from LM Studio and Ollama) and a project folder. If LM Studio's server is off, the app offers a **Start LM Studio server** button, which runs `lms server start` on the computer.
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

Most presets can run on either model server. You pick a model from the LM Studio or Ollama list in the app, and the bridge passes it the way that harness expects.

| id | Runs | LM Studio | Ollama |
|---|---|---|---|
| `pi` | `pi --mode rpc --session-id harness-remote-<id> -e pi-permissions.ts`, one long-lived process per session | `--provider lmstudio --model <id>` | `--provider ollama --model <tag>` |
| `aider` | `aider --message …` (+ `--restore-chat-history`) | `--model lm_studio/<id>`, `LM_STUDIO_API_BASE` | `--model ollama_chat/<tag>`, `OLLAMA_API_BASE` |
| `opencode` | `opencode run …` (+ `--continue`) | `--model lmstudio/<id>` | `--model ollama/<tag>` |
| `codex` | `codex exec --oss --local-provider <server> --sandbox workspace-write …` (+ `resume --last`) | `-m <id>` | `-m <tag>` |
| `goose` | `goose run --name harness-remote-<id> --text …` (+ `--resume`) | n/a | `GOOSE_PROVIDER=ollama GOOSE_MODEL=<tag>` |
| `claude-ollama` | `claude -p --permission-mode acceptEdits …` (+ `--continue`) | n/a | `--model <tag>`, `ANTHROPIC_BASE_URL=<ollama>` (Ollama's Anthropic-compatible API) |
| `ollama-chat` | `ollama run <tag>` in a PTY | n/a | positional |
| `shell` | your login shell in a PTY | n/a | n/a. **Disabled by default** |

Presets whose command isn't installed show up greyed out in the app, with the reason.

- **Pi** runs in RPC mode, so the app shows everything as it happens, the way Claude Code does:
  - streamed thinking (tap to expand)
  - each tool call (`⏺ Bash(npm test)`), with its output streaming live underneath while it runs (stdout and stderr, last 8 lines), then a short result preview (`⎿ …`, tap to expand). This includes trace output from commands that print their progress to stderr, such as a Claude delegation script run with `-T`
  - the reply as it's written
  - a live "✻ Running Bash… (12s)" line

  Sending a message while pi works **steers** it: pi reads it after the current step. Stop aborts cleanly.
- **The status line always says what pi is doing:** waiting for the model (with a hint after 20 s that LM Studio may still be loading or reading the prompt), thinking, writing, *preparing a tool call*, running a tool, retrying or compacting. It shows a rough token count and the elapsed time. While the model writes a tool call, its arguments (the command or file contents) stream in under the tool row.
- **Approvals appear in a panel fixed above the message box,** not inside the scrolling chat. Its buttons ignore taps until the panel has been visible for about half a second, and again while the chat is scrolling, so a scroll can't turn into an accidental Yes or No. In the transcript each question is one line: pending, ✓ allowed, ✗ declined or expired.
- **In a chat:** replies render as markdown. Tap the model line under the title to switch **model** (the conversation carries over: the bridge restarts pi on the new model if pi didn't load it at startup) or **effort** (pi's thinking level, applied right away). **+** attaches up to 4 photos. They're resized on the phone and sent as image input to pi. Pick a model LM Studio marks as *vision*; the bridge sets `"input": ["text","image"]` for it in pi's `models.json`. Other harnesses get photos saved to a temp folder, with the paths added to the prompt.
- **New session** opens in the folder you used last, offers your recent folders as chips, and the path at the top is tappable to jump up.
- **Permissions (pi):** the bridge installs a small pi extension (`~/.harness-remote/pi-permissions.ts`). Before pi runs any tool that isn't read-only (bash, edit, write, MCP tools without a read-only hint), you get a card on the phone, plus a notification if the app is in the background, with **Yes / Yes, and don't ask again for this tool this session / No**. "No" blocks the call, and pi is told you declined. Turn it off per session with "Ask before commands and edits" when creating the session. Read, grep, find and ls never ask. Other harnesses run with their own auto-approve flags (`--yes-always`, `--sandbox workspace-write`, `--permission-mode acceptEdits`), so they never stop to ask.
- **Pi models:** pi only uses models listed in `~/.pi/agent/models.json` (or `$PI_CODING_AGENT_DIR`). When you start a pi session, the bridge adds the model you picked under an `lmstudio` or `ollama` provider if it isn't there yet, with `"reasoning": true` so effort levels apply, and image input for vision models. It never removes or rewrites anything else, and leaves a file that isn't plain JSON untouched. Each phone session maps to its own pi session (`--session-id`), so two sessions in one folder don't share history.
- **OpenCode** needs an `lmstudio` / `ollama` provider block in `opencode.json` ([docs](https://opencode.ai/docs/providers/)).
- Local models usually need a bigger context window than the default for agent work. In LM Studio, raise the context length when loading the model. For Ollama, set `OLLAMA_CONTEXT_LENGTH` (for example 32768).

### LM Studio Bionic

[Bionic](https://lmstudio.ai/docs/bionic) is a desktop app with no CLI, API or remote-control interface, so the bridge can't drive Bionic's own agent sessions. Its models run on the LM Studio runtime, though. Any model LM Studio's server lists (Developer → Start server, or `lms server start`) shows up in the app's **LM Studio** list and works with every harness above. If a model you downloaded in Bionic doesn't appear there, open it in LM Studio, or point LM Studio's models folder at the same location.

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
      "models": ["lmstudio"]
    },
    // a harness that works with both servers
    {
      "id": "my-agent-2",
      "command": "my-agent",
      "args": ["{modelArgs}", "--prompt", "{prompt}"],
      "backends": {
        "lmstudio": { "modelArgs": ["--base-url", "{lmstudio}/v1", "--model", "{model}"] },
        "ollama": { "modelArgs": ["--base-url", "{ollama}/v1", "--model", "{model}"] }
      }
    }
  ]
}
```

Placeholders: `{prompt}` `{model}` `{provider}` (`lmstudio` or `ollama`) `{session}` `{cwd}` `{ollama}` `{lmstudio}`. An argument that is exactly `{modelArgs}` expands to the harness's `modelArgs` when a model is picked, and to nothing otherwise. `modelEnv` is applied only when a model is picked. Arguments go straight to the process with no shell in between, so nothing you type on the phone is shell-interpreted. Other fields: `pty: true`, `stdin: "pipe"`, `requiresModel: true`.

Check what the bridge will offer with `node harness-bridge.mjs --print-config`.

## Security

The bridge runs programs on your computer for whoever holds the token, so treat the token like an SSH key.

**The agent runs with your Windows/macOS/Linux account's full permissions.** The folder you pick is only where it starts. It does not stop the model from touching anything else your account can reach. Local models in particular can produce wrong or dangerous commands. The bridge adds guard rails, described below, but they are a safety net, not a sandbox. For real isolation, run the bridge under a separate user account, in WSL, or in a VM. Keep OneDrive or File History backups switched on.

**Delete guard (pi, always on, including when "Ask before commands and edits" is off):**
- **Blocked outright, with no approve button:** recursive deletes (`rm -rf`, `Remove-Item -Recurse`, `rd /s`, `del /s`, `rimraf`, also inside `cmd /c`, `powershell -c` or `bash -c`, and after `cd`) whose target is any of these:
  - a drive or filesystem root
  - your user folder or anything above it
  - Desktop, Documents, Downloads, Pictures, Music, Videos, OneDrive, AppData, `.ssh`, `.config` and similar
  - the project folder or anything above it
  - system folders
  - a path built from a variable that isn't set

  Also blocked: `format`, `diskpart`, `mkfs`, and `dd of=`. The model is told not to work around a block.
- **Always asked, with No as the first option:** every other delete, plus `git clean -f`, `git reset --hard`, `find -delete`, `robocopy /mir`, and scripts that call `rmtree`, `rmSync` or `Remove-Item`. The card lists the resolved paths.
- **Always asked:** writes and edits to files outside the project folder.
- **Commands (with "Ask before commands and edits" on):**
  - Read-only commands run without asking: `ls`, `cat`, `grep`, `find` (without `-exec` or `-delete`), `git status`, `git log`, `git diff`, `npm ls`, and similar.
  - Anything else shows **Yes / Allow `<program>` commands this session / Allow all non-delete commands this session / No**.
  - A command that chains several programs is only auto-approved if every program in it is allowed.
  - Deletes are never covered by these allowances. They always get the red card, and the blocks above always apply.
  - The guard looks through wrappers such as `env`, `npx`, `npm exec`, `timeout`, `start`, `cmd /c` and `bash -c`, so a delete hidden behind an allowed program is still caught.

The guard reads the command text. It cannot see inside scripts the agent writes to a file and then runs. That is why every command asks by default.

**Other harnesses:**
- Aider runs with `--no-suggest-shell-commands`, so it edits files but never runs shell commands.
- Codex runs in its `workspace-write` sandbox. Check whether your Codex version supports that sandbox on Windows.
- Claude Code runs with `acceptEdits`, which doesn't approve shell commands in `-p` mode.

- **Pairing**: a 6-digit, one-use code, valid for 10 minutes after the bridge starts. Pairing locks after 10 wrong attempts. The phone receives the 32-character random token, and every WebSocket connection is checked against it in constant time.
- **Which programs the phone can start**: only harnesses listed in your config, starting in folders under `roots`. The phone can't run a command itself, but the agent it drives can run any command your account can (see above). The `shell` preset is off unless you enable it.
- **Transport**: plain `ws://` by default. That's fine on a trusted LAN. Anywhere else, use **Tailscale** (WireGuard-encrypted), or set `tls` and the phone uses `https://`/`wss://` (enter the address with `https://`).
- The config and transcripts are written with mode `0600`.

## Protocol

JSON over a WebSocket at `/ws` (`Authorization: Bearer <token>`). Requests carry a `rid` that the reply echoes.

| Client → bridge | Reply |
|---|---|
| `hello` | `{t:"hello", host, version, roots, harnesses[], sessions[]}` (also sent on connect) |
| `models` | `{t:"models", lmstudio[], ollama[], errors{}, lms}` |
| `lms-start` | runs `lms server start`, then replies like `models` |
| `ls {path}` | `{t:"ls", path, parent, dirs[], git}` |
| `create {harness, cwd, model, provider, title, permissions: "ask"\|"auto"}` | `{t:"created", session}` |
| `subscribe {id, after}` | `{t:"history", events[]}`, then live `{t:"event", id, ev}` |
| `send {id, text}` · `stdin {id, data}` · `stop {id}` · `restart {id}` · `rename {id, title}` · `delete {id}` | `{t:"ok"}` or `{t:"error", error}` |
| `answer {id, askId, value \| confirmed \| cancelled}` | answers a permission or question card (`ask` event) |

Event kinds: `user`, `out`, `err`, `status`, `exit`, plus, for RPC harnesses, `think`, `text`, `tool {toolId, tool}`, `toolres {toolId, isError, durationMs}`, `ask {askId, method, options}` and `answer {askId}`.

The bridge also pushes `{t:"sessions", sessions[]}` whenever any session changes state (`idle` / `running` / `waiting` / `exited`). HTTP: `GET /health`, `POST /pair {code}`.

## Limitations

- Output is shown as cleaned text (ANSI stripped, `\r` progress lines collapsed), not as a full terminal emulator. Full-screen TUIs in interactive mode are usable but not pretty. The per-message presets are the intended way to work.
- "Needs input" is a heuristic: output went quiet for 1.5 s and the last line looks like a prompt.
- OpenCode's `--continue` and Codex's `resume --last` pick the most recent session *in that folder*. Two phone sessions on the same folder with the same harness will share history.
- On Windows, the PTY-based presets (`ollama-chat`, `shell`) are unavailable. Per-message harnesses work. npm-installed CLIs (`.cmd` shims) are started through the script they wrap, so prompt text never passes through `cmd.exe`. Other `.bat`/`.cmd` files are refused: point `command` at the real `.exe` or `.js` instead.

## Troubleshooting

| You see | Fix |
|---|---|
| `'node' is not recognized` / `command not found: node` | Install Node.js LTS from nodejs.org, then open a **new** terminal. |
| `Cannot find module …harness-bridge.mjs` | You're in the wrong folder. `cd` to where the file is (e.g. `cd Downloads`) or pass the full path. |
| `SyntaxError` right at startup, or "needs Node.js 18" | Your Node is too old. Install the current LTS. |
| `Port 8787 is already in use` | A bridge is already running (check other windows), or start with `--port 8788` and use that port on the phone. |
| Every harness shows ✗ | None are installed or on `PATH` in that terminal. Install one, open a new terminal, and run the bridge again. |
| Bridge runs but the phone can't connect | Phone and computer must be on the same Wi-Fi (or both on Tailscale). Use an address from the "Reachable at" list, not `0.0.0.0`. On Windows, allow Node.js through Windows Defender Firewall for **Private** networks and set the Wi-Fi network to Private. |
| "Pairing code expired" | Codes last 10 minutes and work once. Restart the bridge for a new one. |
| LM Studio models list is empty | Start LM Studio's server (Developer → Start server, the app's button, or `lms server start`). The bridge expects it on `127.0.0.1:1234`. Change `"lmstudio"` in the config if you moved it. |
| Ollama models list is empty | Ollama isn't running, or isn't on `127.0.0.1:11434`. Set `"ollama"` in the config. `OLLAMA_HOST=0.0.0.0` is handled. |
| Pi says the model isn't found | Check `~/.pi/agent/models.json`. The bridge adds the model under `lmstudio`/`ollama`, but if you already had that provider pointing elsewhere, it keeps your `baseUrl`. |
