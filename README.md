<div align="center">

# Kern

**An AI coding agent that runs entirely on your Android phone.**

*A real terminal with rootless Ubuntu. A real file editor. A live dev-server preview. 29 agent tools. No account, no cloud, no root.*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.03-4285F4.svg?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Gradle](https://img.shields.io/badge/Gradle-8.14.4-02303A.svg?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![Room](https://img.shields.io/badge/Room-2.6.1-2E8B57.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/training/data-storage/room)
[![Hilt](https://img.shields.io/badge/Hilt-2.52-3DDC84.svg?style=flat-square&logo=dagger&logoColor=white)](https://dagger.dev/hilt)
[![minSdk](https://img.shields.io/badge/minSdk-31%20%28Android%2012%29-3DDC84.svg?style=flat-square)](https://developer.android.com/about/versions/12)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-3DDC84.svg?style=flat-square)](https://developer.android.com/about/versions/16)
[![License](https://img.shields.io/badge/License-MIT%20%2B%20GPL--2.0%20%2B%20LGPL--3.0-blue.svg?style=flat-square)](LICENSE)

[![GitHub stars](https://img.shields.io/github/stars/MurShidM01/Kern?style=flat-square&label=%E2%98%85%20Stars&color=gold)](https://github.com/MurShidM01/Kern/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/MurShidM01/Kern?style=flat-square&label=%F0%9F%94%A9%20Forks&color=blue)](https://github.com/MurShidM01/Kern/network/members)
[![GitHub issues](https://img.shields.io/github/issues/MurShidM01/Kern?style=flat-square&label=%F0%9F%93%8B%20Issues&color=critical)](https://github.com/MurShidM01/Kern/issues)
[![GitHub license](https://img.shields.io/github/license/MurShidM01/Kern?style=flat-square&label=License&color=blue)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/MurShidM01/Kern?style=flat-square&label=Last%20Commit&color=success)](https://github.com/MurShidM01/Kern/commits/main)
[![Made with Kotlin](https://img.shields.io/badge/Made%20with-Kotlin-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)

**Android · Kotlin · Jetpack Compose · OpenAI-compatible API · Offline-first · Rootless Linux**

</div>

---

## 📸 Showcase

<div align="center">

### Watch it work — the agent plans, asks permission, then executes

<p align="center">
  <img src="docs/assets/kern-demo.gif" alt="Kern demo: an AI agent inspecting a workspace, requesting command approval, running shell commands and driving a terminal on Android" width="320">
</p>

<sub>
GIF · 380px · 12 fps · 93-second loop — the agent exploring a workspace, a command approval sheet, a plan review, and a live <code>root@localhost:/workspace#</code> terminal.
</sub>

</div>

---

## 🖼️ Screenshots

<div align="center">
<table>
<tr>
<td width="200"><img src="docs/assets/screenshots/07-home-workspaces.jpg" alt="Kern home screen with workspaces, quick actions and recent sessions" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/08-agent-welcome.jpg" alt="Kern agent chat screen with suggested starter tasks" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/19-terminal-ubuntu-prompt.jpg" alt="Kern running a real Ubuntu terminal on Android showing os-release output" width="200"></td>
</tr>
<tr>
<td align="center"><b>Home</b><br><sub>workspaces, quick actions, recent sessions</sub></td>
<td align="center"><b>Agent</b><br><sub>chat, streaming reasoning, tool timeline</sub></td>
<td align="center"><b>Terminal</b><br><sub>real Ubuntu 24.04 LTS over a native PTY</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/10-approval-command.jpg" alt="Command approval sheet asking the user to approve a shell command before it runs" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/11-approval-plan.jpg" alt="Plan review dialog showing the agent's proposed plan for approval" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/14-agent-tool-timeline.jpg" alt="Agent timeline interleaving tool calls with the assistant message that requested them" width="200"></td>
</tr>
<tr>
<td align="center"><b>Command approval</b><br><sub>nothing destructive runs silently</sub></td>
<td align="center"><b>Plan review</b><br><sub>approve, reject, or request changes</sub></td>
<td align="center"><b>Tool timeline</b><br><sub>every step, in order</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/13-todo-tasks.jpg" alt="Todo task list tracking what the agent still has to do" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/15-files-explorer.jpg" alt="Kern file explorer showing a project folder with its files" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/21-linux-environment.jpg" alt="Kern Linux environment screen showing Ubuntu 24.04.1 LTS installed and ready" width="200"></td>
</tr>
<tr>
<td align="center"><b>Todo tasks</b><br><sub>persisted across steps</sub></td>
<td align="center"><b>Files</b><br><sub>explorer and code editor</sub></td>
<td align="center"><b>Linux environment</b><br><sub>Ubuntu 24.04.1 LTS, verified</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/22-packages-all.jpg" alt="Kern package manager listing Ubuntu apt packages" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/16-settings-appearance-light.jpg" alt="Kern settings screen showing colour palettes in light mode" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/18-settings-appearance-dark.jpg" alt="Kern settings screen showing colour palettes in dark mode" width="200"></td>
</tr>
<tr>
<td align="center"><b>Package manager</b><br><sub>real <code>apt</code> inside the guest</sub></td>
<td align="center"><b>Themes</b><br><sub>11 palettes, light and dark</sub></td>
<td align="center"><b>Appearance</b><br><sub>Material 3, dynamic colour</sub></td>
</tr>
</table>
</div>

<details>
<summary><b>📱 Full gallery — setup, providers and approvals (10 more)</b></summary>

<div align="center">
<table>
<tr>
<td width="160"><img src="docs/assets/screenshots/01-setup-workspace.jpg" alt="Setup wizard step 1: choose your workspace" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/02-setup-linux-downloading.jpg" alt="Setup wizard step 2: downloading the Ubuntu rootfs" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/03-setup-linux-extracting.jpg" alt="Setup wizard step 2: extracting the Ubuntu rootfs" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/04-setup-ai-provider.jpg" alt="Setup wizard step 3: connect an AI provider" width="160"></td>
</tr>
<tr>
<td align="center"><sub>1 · Workspace</sub></td>
<td align="center"><sub>2 · Download</sub></td>
<td align="center"><sub>2 · Extract</sub></td>
<td align="center"><sub>3 · Provider</sub></td>
</tr>
<tr>
<td width="160"><img src="docs/assets/screenshots/05-provider-editor.jpg" alt="Provider editor form for an OpenAI-compatible endpoint" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/06-provider-connected.jpg" alt="Provider connection succeeded with a request preview" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/09-agent-first-step.jpg" alt="Agent starting its first step with a working indicator" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/12-agent-preset-tasks.jpg" alt="Preset task suggestions offered to the user" width="160"></td>
</tr>
<tr>
<td align="center"><sub>Provider editor</sub></td>
<td align="center"><sub>Connected</sub></td>
<td align="center"><sub>First step</sub></td>
<td align="center"><sub>Preset tasks</sub></td>
</tr>
<tr>
<td width="160"><img src="docs/assets/screenshots/17-settings-terminal-theme.jpg" alt="Terminal theme selection with the Tango dark theme" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/20-terminal-sessions.jpg" alt="Terminal session sidebar showing multiple sessions and a dev server" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/23-packages-filtered.jpg" alt="Package manager filtered by a search query" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/24-packages-installing.jpg" alt="Package manager installing the sudo package" width="160"></td>
</tr>
<tr>
<td align="center"><sub>Terminal theme</sub></td>
<td align="center"><sub>Sessions</sub></td>
<td align="center"><sub>Search packages</sub></td>
<td align="center"><sub>Installing</sub></td>
</tr>
</table>
</div>

</details>

---

## 🚀 What is Kern?

**Kern** is a **local-first AI coding agent for Android**. It gives a large language model a *real workspace* on your phone — your actual files, a *real shell*, and a *real browser preview* — and then gets out of the way while it works.

There is no cloud workspace to connect to, no SSH tunnel to configure, no root to grant, and no desktop required. The model runs the tool calls; your phone runs the code.

> 🐧 **A real Linux, without root.** Kern bundles a SHA-256-verified [proot](https://proot-me.github.io/) runtime and installs a pinned **Ubuntu 24.04.1 LTS** rootfs on demand. You get a genuine `bash`, real `apt`, a real `gcc` — on a phone, unrooted.

---

## ✨ Feature Highlights

### 🤖 An agent that actually does things

**29 built-in tools** across six areas — the model calls them, and Kern runs them:

| Area | Tools |
|---|---|
| 📁 **Files** | `list_files` `read_file` `search_files` `glob` `grep` `fetch_url` |
| ✏️ **Edit** | `propose_edit` `edit_file` `write_file` `delete_file` `propose_patch` |
| 🔨 **Build** | `run_build` `run_tests` `run_lint` |
| 🐚 **Shell** | `run_command` `terminal_status` |
| 🌿 **Git** | `git_status` `git_diff` `git_commit` |
| 🧠 **Agent** | `update_todos` `propose_plan` `remember` |
| 🖥️ **Server** | `server_start` `server_stop` |
| 👁️ **Preview** | `preview_open` `preview_status` `preview_console` `preview_eval` `preview_screenshot` |

**The loop is careful by design:**

- **Read-only tools run in parallel**; mutating tools run in sequence, behind approval.
- **Permission modes** — `Ask changes`, `Auto edit`, `Full access` — decide what needs your sign-off.
- **Nothing destructive runs silently.** `rm -rf`, `mkfs`, `git reset --hard` always ask.
- **A failing tool never aborts the turn** — it returns an error the model can read and recover from.
- **Malformed tool arguments are repaired centrally**, so a model that guesses `file_path` instead of `path` still works.

### 🐧 A real terminal, not a wrapper around `sh`

Most Android terminals fake it. Kern does not:

- A **hand-written POSIX PTY** in C — `posix_openpt` → `fork` → `setsid` → `TIOCSCTTY` → `execve` — so the guest gets a *controlling terminal* and job control, prompts and TUIs all behave.
- A **pure-Kotlin VT/ANSI emulator** with primary and alternate screen buffers, bounded scrollback, scroll regions, 256-colour and truecolour SGR, and East-Asian wide-character handling.
- A **bidirectional terminal**: the emulator returns response bytes for `DSR`, `DA` and OSC colour queries, instead of only rendering.
- The `TerminalEmulator` is a plain `kotlin-jvm` module, so it is unit-testable without a device.

### 👁️ See what the agent built

Start a dev server from the agent and Kern opens a **live WebView preview** with a browser-style console drawer. The agent can read that console, evaluate JavaScript in the page, and take screenshots — all without you leaving the app.

The preview is locked to the loopback dev server, and the only JavaScript bridge exposed is a `report(level, message, source, line)` console hook, so that bridge is never reachable from a remote page.

### 🔒 Private by construction

- API keys are encrypted with **AES-256-GCM** using the **Android Keystore** — never stored in plain text, never written to a log.
- Works with **any OpenAI-compatible endpoint**, including a local one.
- **No telemetry, no account, no server.** The app is the product.

---

## 🧩 Architecture

Kern is a **25-module Gradle project** with a strict `core/*` + `feature/*` layering. `core:model` is pure Kotlin with no Android dependency, and `core:terminal:engine` is a plain JVM module — both are unit-testable on the JVM.

```
:app                       Navigation shell · setup wizard · foreground services
:core:model                Domain models and enums (pure Kotlin, no Android)
:core:common               Dispatchers · logging · secret redaction
:core:designsystem         Theme tokens · 11 palettes · shared Compose components
:core:data                 Room v4 · DataStore · Keystore-encrypted credentials
:core:workspace            Workspace lifecycle · adapters · execution mirror
:core:providers:api        Provider-neutral LLM contracts
:core:providers:openai     OpenAI-compatible SSE streaming adapter
:core:agent                The agent loop · context builder · workspace memory
:core:tools                29 tools · registry · argument-repair layer
:core:auth                 API-key auth coordinator
:core:terminal:api         TerminalRuntime contract
:core:terminal:engine      Pure-Kotlin VT/ANSI emulator (JVM)
:core:terminal:pty         Native POSIX PTY bridge (C + CMake + NDK)
:core:terminal:binaries    Vendored proot runtime, SHA-256 pinned
:core:terminal:implementation  Layered runtime · sessions · distro installer
:feature:home              Dashboard
:feature:files             File explorer and code editor
:feature:agent             Agent chat, approvals, plan review
:feature:settings          Appearance, provider profiles, model catalogue
:feature:terminal          Terminal UI, environment setup, package manager
:feature:preview           Live WebView preview and console drawer
```

### How the pieces fit

```
   ┌──────────────┐   AgentEvent    ┌───────────────┐
   │  :app        │◄───────────────►│  :core:agent  │
   │  NavHost     │                 │  the loop     │
   └──────┬───────┘                 └───────┬───────┘
          │                                 │
          │  ┌──────────────────────────────┴──────────────┐
          ▼  ▼                                             ▼
   ┌─────────────┐   tool calls    ┌──────────────┐   ┌───────────────┐
   │ :core:tools │◄───────────────►│ :core:workspace│  │ :core:providers│
   └──────┬──────┘                 └───────────────┘   └───────┬───────┘
          │                                                    │
          │ command                                    SSE stream │
          ▼                                                    ▼
   ┌──────────────────────┐                          ┌────────────────────┐
   │ :core:terminal       │                          │ :core:providers:   │
   │ impl → pty → proot   │                          │ openai             │
   │ real Ubuntu 24.04    │                          └────────────────────┘
   └──────────────────────┘
```

---

## 🛡️ Safety Model

Nothing happens silently. Every command is classified before it runs:

| Impact | Examples | Behaviour |
|---|---|---|
| 🟢 **Read-only** | `ls` `grep` `cat` `git status` | Runs freely |
| 🟡 **Mutating** | `npm install` `git add` `write_file` | Gated by permission mode |
| 🟠 **Network** | `curl` `npm publish` | Gated by permission mode |
| 🔴 **Destructive** | `rm -rf` `mkfs` `git reset --hard` | **Always asks** |

Sensitive values are redacted from tool arguments, URLs, headers and error messages before anything is logged or displayed. The classifier is a *safety signal only* — it never executes anything, and it errs toward a higher impact when unsure.

---

## 🚀 Getting Started

### Requirements

| | |
|---|---|
| **Android** | 12 (API 31) or newer |
| **JDK** | 17 or newer |
| **Android SDK** | API 36 |
| **Gradle** | 8.14.4 (via the bundled wrapper) |
| **Arch** | `arm64-v8a` or `x86_64` |

### Build from source

```bash
git clone https://github.com/MurShidM01/Kern.git
cd Kern
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

```bash
# install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Run the tests

```bash
./gradlew testDebugUnitTest     # 466 unit tests
```

### First launch

1. **Point it at a folder** — an existing folder via the system picker, or an app-managed workspace inside Kern's private storage.
2. **Install Ubuntu** *(optional)* — a ~65 MB SHA-256-verified download, staged and promoted atomically, so a failed download never breaks a working install. The built-in shell works without it.
3. **Add a provider** — any OpenAI-compatible base URL and API key. Kern tests the connection before saving.
4. **Ask it to do something.**

> 💡 **Tip:** grant **All files access** and Files, the Editor, the Agent and the Terminal all share one real `/sdcard` tree. Without it, the terminal runs against a private mirror that you can sync back explicitly.

---

## 🧪 Testing

**466 unit tests** across 66 suites, all passing.

| Module | Coverage |
|---|---|
| `:core:agent` | Agent loop, parallel tools, approvals, context builder, compaction, workspace memory |
| `:core:tools` | Every tool, argument repair, diffing, patch application, preview and dev-server seams |
| `:core:terminal:engine` | VT parser, emulator, SGR, scrollback, text widths |
| `:core:terminal:implementation` | Installer, extractor, command executor, session manager, dev servers, proot launcher |
| `:core:data` | Room DAOs, migrations 1→4, mappers, preferences |
| `:core:workspace` | Path containment, external path resolution, workspace adapters, execution mirror |
| `:core:providers:openai` | SSE streaming and error handling against a mock server |
| `:feature:*` | ViewModel behaviour for agent, terminal, preview |

**Stack:** JUnit 4 · Truth · Robolectric · Turbine · MockK · MockWebServer · Room testing · Compose UI test.

---

## 🧱 Notable engineering decisions

- **Two "workspace" concepts.** A *logical* workspace (`WorkspaceManager`, `WorkspaceAdapter`) and an *execution* workspace (`ExecutionWorkspaceManager`, `ExecutionMirror`). They diverge when a SAF folder lacks all-files access, and the mirror is pure over a tree seam so it is testable without Android.
- **`@Upsert`, never `@Insert(REPLACE)`.** `REPLACE` deletes and re-inserts, firing `ON DELETE CASCADE` and silently wiping sessions. A regression test locks this in.
- **The turn is not owned by `viewModelScope`.** It lives in an application-scoped `AgentSessionStore`, so navigating away does not cancel the provider stream. A foreground service stops Android freezing the process mid-turn.
- **The terminal pumps with `emit`, not `tryEmit`.** A bounded `SharedFlow` *drops* values when full, which silently truncated the package catalogue. Suspending applies backpressure through the PTY instead.
- **Schema-driven argument repair.** `ToolArgs.normalize` fixes alias mismatches, wrapper envelopes, stringified types and embedded JSON in one place rather than in 29 tools.
- **The vendored binaries are SHA-256 pinned** in `binaries.lock.json` and re-verified on every build, so a tampered `.so` fails the build rather than the device.

---

## 📄 License

Kern's own source code is licensed under the **MIT License** — see [`LICENSE`](LICENSE).

The bundled third-party runtime keeps its own license, and the full texts ship inside the APK under `assets/terminal/licenses/`:

| Component | Version | License |
|---|---|---|
| [proot](https://proot-me.github.io/) | 5.1.107.96 | **GPL-2.0** |
| [libtalloc](https://www.samba.org/amba/talloc/) | 2.5.0 | **LGPL-3.0** |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | 0.7 | **MIT** |

> ⚠️ **Note:** the `proot` binary is distributed under **GPL-2.0**, and `libtalloc` under **LGPL-3.0**. Kern's own source remains MIT, but the repository as a whole is **MIT + GPL-2.0 + LGPL-3.0** because of the redistributed binaries in `core/terminal/binaries/`.

---

## 🤝 Contributing

Contributions are very welcome — issues, ideas and pull requests.

```bash
git clone https://github.com/MurShidM01/Kern.git
cd Kern
./gradlew testDebugUnitTest   # 466 tests
./gradlew assembleDebug
```

**Please do not commit** build output, device screenshots, or API keys — `.gitignore` already covers them. Two things **must** stay committed:

- `core/data/schemas/` — the exported Room schemas are the source of truth for migrations.
- `core/terminal/binaries/src/main/jniLibs/` — the vendored proot runtime, verified by hash at build time.

---

## 🔗 Related

- [proot](https://proot-me.github.io/) — the userspace chroot, seccomp and ptrace emulator that makes rootless Linux work on Android
- [Termux](https://termux.dev/) — where the vendored binaries and `TERMUX` build conventions come from
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — the entire UI
- [Room](https://developer.android.com/training/data-storage/room) · [Hilt](https://dagger.dev/hilt) · [OkHttp](https://square.github.io/okhttp/)

---

<div align="center">

**Built with Kotlin, Jetpack Compose, and a lot of C.**

[![Back to top](https://img.shields.io/badge/-%20Back%20to%20top-7F52FF.svg?style=flat-square&logo=github&logoColor=white)](#kern)

</div>
