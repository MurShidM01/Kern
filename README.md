<div align="center">

# Kern

**The AI coding agent and Linux terminal that runs entirely on your Android phone.**

*Read and edit your files. Run real shell commands in a real Ubuntu. Preview your web app live. Twenty-nine agent tools. No account, no cloud, no root, no desktop.*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.03-4285F4.svg?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Gradle](https://img.shields.io/badge/Gradle-8.14.4-02303A.svg?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![Room](https://img.shields.io/badge/Room-2.6.1-2E8B57.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/training/data-storage/room)
[![Hilt](https://img.shields.io/badge/Hilt-2.52-3DDC84.svg?style=flat-square&logo=dagger&logoColor=white)](https://dagger.dev/hilt)
[![minSdk](https://img.shields.io/badge/minSdk-28%20%28Android%209%29-3DDC84.svg?style=flat-square)](https://developer.android.com/about/versions/9)
[![targetSdk](https://img.shields.io/badge/targetSdk-37-3DDC84.svg?style=flat-square)](https://developer.android.com/about/versions/17)
[![License](https://img.shields.io/badge/License-MIT%20%2B%20GPL--2.0%20%2B%20LGPL--3.0-blue.svg?style=flat-square)](LICENSE)

[![GitHub stars](https://img.shields.io/github/stars/MurShidM01/Kern?style=flat-square&label=Stars&color=gold)](https://github.com/MurShidM01/Kern/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/MurShidM01/Kern?style=flat-square&label=Forks&color=blue)](https://github.com/MurShidM01/Kern/network/members)
[![GitHub issues](https://img.shields.io/github/issues/MurShidM01/Kern?style=flat-square&label=Issues&color=critical)](https://github.com/MurShidM01/Kern/issues)
[![Last commit](https://img.shields.io/github/last-commit/MurShidM01/Kern?style=flat-square&label=Last%20Commit&color=success)](https://github.com/MurShidM01/Kern/commits/main)

**Android · AI agent · Linux terminal · Ubuntu · proot · OpenAI-compatible · Offline-first · Rootless · Open source**

</div>

---

<div align="center">

### [Read the website](https://murshidm01.github.io/Kern/) · [Documentation](https://murshidm01.github.io/Kern/docs.html) · [How to use](https://murshidm01.github.io/Kern/how-to.html)

</div>

---

## Kern: an AI coding agent for Android

**Kern** turns your Android phone into a complete development environment. It pairs a real Linux terminal with an AI agent that can read your files, edit them, run your commands and check the result, all from a chat window.

If you have ever wanted to run a shell on your phone, use an AI assistant on Android, or carry a full Ubuntu userspace in your pocket without rooting, Kern was built for exactly that. There is no cloud workspace to connect to, no SSH tunnel to configure, and no desktop required. The AI decides what to do, and your phone runs it.

Kern is free, open source, and works with any OpenAI-compatible AI model, including one you host yourself.

---

## Demo

<div align="center">
<p><img src="docs/assets/kern-demo.gif" alt="Kern AI coding agent on Android: the agent explores a project folder, requests approval before running a shell command, reviews its plan, and finishes in a live Ubuntu terminal" width="340"></p>
<sub>380px, 12 fps, 93-second loop. The agent explores a project, asks to run a command, reviews a plan, and finishes at a live <code>root@localhost:/workspace#</code> prompt.</sub>
</div>

---

## Screenshots

<div align="center">
<table>
<tr>
<td width="200"><img src="docs/assets/screenshots/07-home-workspaces.jpg" alt="Kern home screen showing workspaces, quick actions and recent AI agent sessions" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/08-agent-welcome.jpg" alt="Kern AI agent chat screen with suggested coding tasks and a model picker" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/19-terminal-ubuntu-prompt.jpg" alt="Kern terminal emulator running real Ubuntu 24.04 LTS on an unrooted Android phone" width="200"></td>
</tr>
<tr>
<td align="center"><b>Home</b><br><sub>workspaces, quick actions, recent sessions</sub></td>
<td align="center"><b>AI agent</b><br><sub>chat, streaming reasoning, tool timeline</sub></td>
<td align="center"><b>Terminal</b><br><sub>real Ubuntu 24.04 over a native PTY</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/10-approval-command.jpg" alt="Kern asks the user to approve a shell command before the AI agent is allowed to run it" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/11-approval-plan.jpg" alt="Kern plan review dialog where the user approves or rejects the AI agent's proposed plan" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/14-agent-tool-timeline.jpg" alt="Kern AI agent timeline interleaving every tool call with the message that requested it" width="200"></td>
</tr>
<tr>
<td align="center"><b>Command approval</b><br><sub>nothing destructive runs silently</sub></td>
<td align="center"><b>Plan review</b><br><sub>approve, reject, or request changes</sub></td>
<td align="center"><b>Tool timeline</b><br><sub>every step, in order</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/13-todo-tasks.jpg" alt="Kern todo list tracking the tasks the AI agent still has to finish" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/15-files-explorer.jpg" alt="Kern file explorer browsing a project folder on the Android device" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/21-linux-environment.jpg" alt="Kern Linux environment manager showing Ubuntu 24.04.1 LTS installed and ready" width="200"></td>
</tr>
<tr>
<td align="center"><b>Todo tasks</b><br><sub>persisted across steps</sub></td>
<td align="center"><b>Files</b><br><sub>explorer and code editor</sub></td>
<td align="center"><b>Linux environment</b><br><sub>Ubuntu 24.04.1 LTS, verified</sub></td>
</tr>
<tr>
<td width="200"><img src="docs/assets/screenshots/22-packages-all.jpg" alt="Kern package manager browsing the full Ubuntu apt package catalogue" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/16-settings-appearance-light.jpg" alt="Kern settings screen offering eleven colour palettes in light mode" width="200"></td>
<td width="200"><img src="docs/assets/screenshots/18-settings-appearance-dark.jpg" alt="Kern settings screen offering eleven colour palettes in dark mode" width="200"></td>
</tr>
<tr>
<td align="center"><b>Package manager</b><br><sub>real <code>apt</code> inside the guest</sub></td>
<td align="center"><b>Themes</b><br><sub>11 palettes, light and dark</sub></td>
<td align="center"><b>Appearance</b><br><sub>Material 3, dynamic colour</sub></td>
</tr>
</table>
</div>

<details>
<summary><b>Full gallery: setup wizard, AI provider and approvals</b></summary>

<div align="center">
<table>
<tr>
<td width="160"><img src="docs/assets/screenshots/01-setup-workspace.jpg" alt="Kern setup wizard step 1: choose a workspace folder for the project" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/02-setup-linux-downloading.jpg" alt="Kern setup wizard step 2: downloading the Ubuntu root filesystem" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/03-setup-linux-extracting.jpg" alt="Kern setup wizard step 2: extracting the Ubuntu root filesystem" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/04-setup-ai-provider.jpg" alt="Kern setup wizard step 3: connect an AI provider" width="160"></td>
</tr>
<tr>
<td align="center"><sub>1 &middot; Workspace</sub></td>
<td align="center"><sub>2 &middot; Download</sub></td>
<td align="center"><sub>2 &middot; Extract</sub></td>
<td align="center"><sub>3 &middot; Provider</sub></td>
</tr>
<tr>
<td width="160"><img src="docs/assets/screenshots/05-provider-editor.jpg" alt="Kern AI provider editor for any OpenAI-compatible endpoint" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/06-provider-connected.jpg" alt="Kern successfully connected to an AI provider with a request preview" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/09-agent-first-step.jpg" alt="Kern AI agent running its first step with a live working indicator" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/12-agent-preset-tasks.jpg" alt="Kern preset coding task suggestions for the AI agent" width="160"></td>
</tr>
<tr>
<td align="center"><sub>Provider editor</sub></td>
<td align="center"><sub>Connected</sub></td>
<td align="center"><sub>First step</sub></td>
<td align="center"><sub>Preset tasks</sub></td>
</tr>
<tr>
<td width="160"><img src="docs/assets/screenshots/17-settings-terminal-theme.jpg" alt="Kern terminal theme picker with the Tango dark terminal theme" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/20-terminal-sessions.jpg" alt="Kern terminal session sidebar showing multiple shells and a dev server" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/23-packages-filtered.jpg" alt="Kern package manager filtered by a search query" width="160"></td>
<td width="160"><img src="docs/assets/screenshots/24-packages-installing.jpg" alt="Kern package manager installing a package inside Ubuntu" width="160"></td>
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

## Why Kern

### Run a real Linux terminal on Android without root

Kern installs a genuine Ubuntu 24.04.1 LTS userspace on your phone, on demand. You get a real `bash`, real `apt`, a real `gcc`, real Python and a real Git, all inside a real pseudo-terminal, so job control, shell prompts and interactive programs behave the way they should.

The terminal is not a wrapper around a limited `sh`. It is a proper POSIX PTY driving a full VT and ANSI emulator, with scrollback, alternate screen buffers, 256-colour and truecolour support, and correct handling of wide characters.

No root. No bootloader modification. No voidspace.

### An AI agent that edits your files and runs your commands

Kern ships twenty-nine tools the model can call, covering files, edits, builds, tests, Git, shell commands, dev servers, previews, plans and memory. Ask for something in plain language and the agent plans, asks permission where it matters, and then gets it done.

| Area | Tools |
| --- | --- |
| Files | `list_files` `read_file` `search_files` `glob` `grep` `fetch_url` |
| Edit | `propose_edit` `edit_file` `write_file` `delete_file` `propose_patch` |
| Build | `run_build` `run_tests` `run_lint` |
| Shell | `run_command` `terminal_status` |
| Git | `git_status` `git_diff` `git_commit` |
| Agent | `update_todos` `propose_plan` `remember` |
| Server | `server_start` `server_stop` |
| Preview | `preview_open` `preview_status` `preview_console` `preview_eval` `preview_screenshot` |

You stay in control. Read-only tools run freely. Mutating tools are gated by your permission mode. Anything destructive always asks first, every single time.

### Preview your web app live on your phone

Ask the agent to start your dev server and it does. Kern then opens a live WebView preview with a browser-style console drawer, so you can watch your site or app render and interact with it without leaving the phone.

The agent can read that console, evaluate JavaScript in the page, and capture screenshots, so it can verify its own work instead of guessing.

### Your API keys never leave your device

Your provider credentials are encrypted with AES-256-GCM using the Android Keystore. They are never stored in plain text and never written to a log. Kern has no account system, no telemetry, and no server of its own.

### Works with any AI model you like

Point Kern at any OpenAI-compatible endpoint: OpenAI, OpenRouter, Together, Groq, a local Ollama or llama.cpp server, or your own gateway. Bring your own key and your own choice of model.

---

## Use cases

Kern is built for the things people actually want to do on a phone.

- **Learn Linux on Android.** A real terminal, a real package manager, and a built-in guide to `apt`.
- **Review and edit code on the go.** Open a project folder, let the agent explain it, then change it.
- **Run scripts and automation.** Cron-style jobs, a quick Python script, a build you started before leaving home.
- **Test your web app away from your desk.** Start the dev server, open the preview, check the console.
- **Learn with a tutor that can actually run things.** An AI that can execute what it explains.
- **Emergency shell access.** A working `bash` on a device with no monitor, no keyboard and no root.
- **Private, offline-capable work.** Point Kern at a model running on your own machine and nothing leaves your network.
- **Continue a project away from your desk.** Edit the same folder in VS Code on your laptop and in Kern on your phone.

---

## Frequently asked questions

**Is Kern free?**
Yes. Kern is free and open source. Its own code is MIT licensed.

**Does Kern require root?**
No. It uses [proot](https://proot-me.github.io/), a userspace chroot and syscall emulator, so a full Linux environment runs on an ordinary, unrooted phone.

**Which Android versions are supported?**
Android 9 (API 28) and newer, on `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` devices.

**Which AI models work with Kern?**
Any OpenAI-compatible endpoint. That includes OpenAI and ChatGPT, OpenRouter, Together, Groq, vLLM, Ollama, llama.cpp, LM Studio and custom gateways. The model list is fetched from the provider, and you pick which one the agent uses. If you would rather not send anything to a cloud, run the model on your own machine and point Kern at it for a fully offline workflow.

**Can I use Claude or Gemini with Kern?**
Not directly yet: Kern ships an OpenAI-compatible adapter, and native Anthropic and Gemini adapters are still to come. In the meantime, an OpenAI-compatible gateway such as OpenRouter can serve those models to Kern today.

**Do I need my own API key?**
Yes, and that is the point. You supply the key, it is encrypted on your device, and you can point it at a local model if you would rather not send anything to a cloud provider.

**Does my code get uploaded anywhere?**
No. Your files stay on your device. Only the prompts and tool results the agent needs travel to the AI provider you configured, and if that provider is local, nothing leaves your network.

**Does the Ubuntu install need much space?**
About 65 MB to download and roughly 375 MB once installed on an `arm64` device. The built-in Android shell works even if you never install it.

**Can I use my own folder as the workspace?**
Yes. Point Kern at any folder through the system file picker, or let it manage one in private app storage. Granting All files access lets the file explorer, the editor, the agent and the terminal all share one real folder.

**Is a desktop version coming?**
Not at the moment. Kern is built for Android first, and the phone is the point.

**Can I use Kern instead of VS Code?**
Kern is not a VS Code replacement, and it is not trying to be one. It is the phone-sized equivalent for quick edits, quick reviews and small scripts, with an agent that can do the work for you. Because it works on a shared folder, the two work well together: build on your laptop in VS Code, then review, tweak or test from the phone in Kern.

**Does Kern work without an internet connection?**
The terminal, the file explorer, the editor, the package manager and the preview all work offline. Only the AI agent needs a model, and if you run that model locally, Kern is fully offline too.

**How is this different from Termux?**
Termux gives you a terminal and leaves the rest to you. Kern gives you a terminal, and adds an AI agent that can work inside it, a file explorer and editor, a package manager, and a live preview of whatever you are building. They also work well together: Kern can point at a Termux installation.

---

## Requirements

| | |
| --- | --- |
| Android | 9 (API 28) or newer |
| JDK | 17 or newer |
| Android SDK | API 37 |
| Gradle | 8.14.4, via the bundled wrapper |
| Architecture | `arm64-v8a`, `armeabi-v7a`, `x86` or `x86_64` |

---

## Build from source

```bash
git clone https://github.com/MurShidM01/Kern.git
cd Kern
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

```bash
# install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

---

## First launch

1. **Choose a workspace.** Point the app at an existing folder, or let it create one in private storage.
2. **Install Ubuntu**, if you want the full Linux environment. It is optional; the built-in shell works without it.
3. **Add an AI provider.** Any OpenAI-compatible base URL and API key. Kern checks the connection before saving.
4. **Ask for something.** Start with "explain this project" and work up from there.

---

## Security and privacy

- API keys are encrypted with **AES-256-GCM** through the **Android Keystore** and are never logged.
- Secrets are stripped from URLs, headers, tool arguments and error messages before anything is written to a log.
- Command impact is classified before execution, and destructive commands always require explicit approval.
- The live preview is restricted to the loopback dev server, and exposes only a console-reporting bridge to JavaScript, never to remote pages.
- There is no account, no telemetry, and no Kern server.

---

## Contributing

Contributions are welcome, and a first contribution can be small. Fixing a bug, removing dead code, adding a missing test, improving an error message or documenting something confusing all help.

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

---

## License

Kern's own source code is licensed under the MIT License. See [`LICENSE`](LICENSE).

The bundled Linux runtime keeps its own license, and the full texts ship inside the app:

| Component | Version | License |
| --- | --- | --- |
| [proot](https://proot-me.github.io/) | 5.1.107.96 | GPL-2.0 |
| [libtalloc](https://www.samba.org/amba/talloc/) | 2.5.0 | LGPL-3.0 |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | 0.7 | MIT |

The `proot` binary is distributed under GPL-2.0 and `libtalloc` under LGPL-3.0, so the project as a whole is MIT, GPL-2.0 and LGPL-3.0.

---

## Related projects

- [proot](https://proot-me.github.io/), the userspace chroot and syscall emulator behind rootless Linux on Android
- [Termux](https://termux.dev/), the terminal environment Kern borrows its runtime conventions from
- [Jetpack Compose](https://developer.android.com/jetpack/compose), the toolkit the interface is built with
- [Room](https://developer.android.com/training/data-storage/room), [Hilt](https://dagger.dev/hilt) and [OkHttp](https://square.github.io/okhttp/)

---

<div align="center">

**A full Linux terminal and an AI coding agent, in your pocket.**

[![Back to top](https://img.shields.io/badge/-%20Back%20to%20top-7F52FF.svg?style=flat-square&logo=github&logoColor=white)](#kern)

</div>
