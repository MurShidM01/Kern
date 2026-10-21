# Contributing to Kern

Thank you for taking the time to contribute. Kern is a large project with a lot of moving parts, and every contribution helps, whether it fixes a single typo, removes a piece of dead code, or adds a whole feature.

This guide covers what to expect, how to set things up, and the handful of conventions that keep the codebase consistent.

---

## Table of contents

- [Ways to contribute](#ways-to-contribute)
- [Good first issues](#good-first-issues)
- [Before you start](#before-you-start)
- [Setting up](#setting-up)
- [Running the tests](#running-the-tests)
- [Code style](#code-style)
- [Commit conventions](#commit-conventions)
- [Pull requests](#pull-requests)
- [Reporting bugs](#reporting-bugs)
- [Finding dead code](#finding-dead-code)
- [Things that need extra care](#things-that-need-extra-care)
- [Repository rules](#repository-rules)
- [Adding a tool](#adding-a-tool)
- [Adding a provider](#adding-a-provider)
- [Code of conduct](#code-of-conduct)

---

## Ways to contribute

You do not need to be an Android expert, or comfortable with native code, to help.

**Fixing bugs.** The issue tracker is the place to start. Reproduce the bug, find the cause, write a test that fails, then fix it. A pull request that includes the regression test is worth more than one without.

**Removing dead code.** This project has accumulated code that is no longer reachable: unused parameters, superseded helpers, commented-out blocks, duplicated logic between modules, and abstractions with a single implementation that no longer earn their keep. If you find something that is clearly dead, delete it and explain why in the pull request. Dead code is a liability, because the next person has to read it and work out whether it matters.

**Tightening tests.** Several modules have no unit tests at all, including `core:model`, `core:auth`, `feature:home`, `feature:files` and `feature:settings`. Adding tests for uncovered behaviour is one of the most useful contributions you can make, and it is a good way to learn the codebase.

**Improving the documentation.** KDoc on non-obvious classes, comments that explain *why* rather than *what*, and a clearer module boundary all count. So do README fixes and a better error message.

**Adding features.** New tools, new providers, new terminal capabilities. Read [Things that need extra care](#things-that-need-extra-care) first, because some parts of this project carry obligations that are easy to miss.

**Reviewing.** You do not need write access to be useful. Reproducing a reported bug, confirming a fix on a real device, or reviewing a pull request for correctness all help.

---

## Good first issues

Issues labelled `good first issue` are scoped so that they can be finished in an afternoon. They are usually one of:

- Adding a unit test for a class that has none
- Removing an unused function, parameter or import
- Fixing a lint warning or a Detekt finding
- Improving an error message so it says what to do next
- Extracting a long function into something testable

If an issue is not labelled but looks small, it probably is. Open it and ask.

---

## Before you start

**For anything beyond a small fix, open an issue first.** It costs you five minutes and it stops two people writing the same thing. A good issue says what you want to change, why, and how you would test it. If you would rather not wait, say so in the issue and start anyway. Nobody will mind.

**For substantial changes, please open a pull request as a draft.** That way we can talk about the approach before you write the whole thing, and you avoid a large rewrite based on a wrong assumption.

**For anything that touches the terminal, the security boundary, or the vendored binaries, please open an issue before writing code.** Those areas have constraints that are not obvious from the code, and it is much easier to agree on an approach up front than to rework it later.

---

## Setting up

### Requirements

| | |
| --- | --- |
| Android | 12 (API 31) or newer, on a device or emulator |
| JDK | 17 or newer |
| Android SDK | API 36 |
| Gradle | 8.14.4, via the bundled wrapper |
| Architecture | `arm64-v8a` or `x86_64` |

You do not need a local Gradle install. The wrapper downloads the right version.

### Clone and build

```bash
git clone https://github.com/MurShidM01/Kern.git
cd Kern
./gradlew assembleDebug
```

### Run on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aliedu.kern.agent.debug/com.aliedu.kern.agent.MainActivity
```

The debug build uses the application id `com.aliedu.kern.agent.debug`, so it installs alongside a release build.

### Open in Android Studio

Open the repository root. Android Studio picks up the version catalog and the Gradle wrapper automatically. Let the first sync finish before building; the native module downloads the NDK if it is not already present.

---

## Running the tests

```bash
./gradlew testDebugUnitTest          # all 466 unit tests
./gradlew :core:agent:testDebugUnitTest   # one module
./gradlew :core:agent:test --tests "*AgentOrchestratorTest"   # one class
```

Instrumented tests need a connected device or a running emulator:

```bash
./gradlew connectedDebugAndroidTest
```

A pull request should not break any existing test. If you change behaviour that a test asserts, update the test and say why in the description. If a test is wrong, fix it, and make that clear in the description so a reviewer can check the reasoning.

---

## Code style

The project is 100% Jetpack Compose with no XML layouts, and the code follows the conventions of the surrounding file rather than any personal style.

**General**

- Four-space indentation, no tabs.
- Maximum line length is 120 characters.
- Trailing commas on multi-line argument and parameter lists.
- One public class per file, and the file name matches the class.
- Prefer expression bodies over block bodies for single-expression functions.
- Prefer `val` over `var` unless reassignment is genuinely required.

**Kotlin specifics**

- No `!!`. Handle the null, or use a safe call, or make the type non-null at the boundary.
- No `println`. Use `KernLog` from `:core:common`, which is gated on debug builds and never logs secrets.
- No global mutable state. The project uses constructor injection through Hilt, and coroutines through `DispatcherProvider` so tests stay deterministic.
- Prefer `sealed interface` over a sealed class with an `object` subtype when the hierarchy has no shared state.
- Suspend functions do not use `runBlocking` outside tests.
- Never block a thread the coroutine dispatcher owns. The terminal and the agent loop both run on IO dispatchers.

**Comments and KDoc**

Comment the *why*, not the *what*. The code already says what it does.

```kotlin
// Bad: increments the counter
counter++

// Good: the exit code sentinel is read from a rolling tail, not the
// capped output buffer, or a large listing drops it and looks like a hang
```

Public APIs carry KDoc. Where a class exists because of a non-obvious constraint, say what that constraint is and link to the issue or test that pins it down.

---

## Commit conventions

The project uses [Conventional Commits](https://www.conventionalcommits.org/). A commit subject looks like this:

```
<type>(<scope>): <imperative summary in the lower case>
```

**Types**

| Type | Use for |
| --- | --- |
| `feat` | A new capability |
| `fix` | A bug fix |
| `docs` | Documentation only |
| `test` | Tests only |
| `refactor` | A change that neither fixes a bug nor adds a feature |
| `perf` | A measurable performance improvement |
| `build` | Build system, dependencies, Gradle files |
| `chore` | Maintenance that fits nothing above |
| `ci` | CI configuration |

**Scopes** are the module or area: `agent`, `tools`, `terminal`, `workspace`, `data`, `providers`, `designsystem`, `preview`, `files`, `settings`, `home`, `app`, `build`, `docs`.

**The subject line** is imperative, lower case, and under 72 characters. Do not end it with a period.

**The body** explains what changed and why. It is wrapped at 72 characters. A body is required whenever the reason is not obvious from the subject, and it should explain the reasoning rather than restate the diff.

```
fix(terminal): read the exit sentinel from a rolling tail

The sentinel was searched in the capped output buffer, so any command
that produced more than the cap dropped the sentinel and the command
looked like it had hung. The tail is now tracked separately and the
buffer can stay capped.

Fixes #412
```

**One logical change per commit.** If a commit touches three unrelated things, split it. A reviewer should be able to read a single commit and understand one idea.

---

## Pull requests

**Keep them focused.** One pull request, one concern. A refactor plus a feature plus a formatting sweep is three pull requests.

**Describe the change.** Say what you changed, why, and how you tested it. A reviewer who has to read the diff to work out what you did will not review it carefully.

**Link the issue** with `Fixes #123` in the description, so GitHub closes it automatically.

**Say how you tested it.** Which tests you added, what you ran, and what you checked on a real device. If you could not test on a device, say so plainly. That is genuinely useful information for a reviewer.

**Add a test.** Every behaviour change needs a test that would fail without it. Exceptions are reasonable and should be explained: a change to a comment, a rename with no behaviour change, or a fix to the native layer where a unit test would only assert the implementation.

**Keep the build green.**

```bash
./gradlew testDebugUnitTest assembleDebug
```

**Do not reformat code you are not changing.** A drive-by reformat makes a diff impossible to review. If you want to reformat a file, put that in its own pull request.

---

## Reporting bugs

A good bug report saves a day of work. Please include:

- **Device and Android version.** For example, a V2109 running Android 13, arm64-v8a.
- **Kern version**, from the app's settings screen or the commit you built.
- **What you did**, step by step.
- **What you expected**, and **what happened** instead.
- **Screenshots or a screen recording.** This is the single most useful thing you can attach.
- **Logs**, if you can get them:

  ```bash
  adb logcat -s Kern:V | grep -i kern
  ```

  Verbose logging is enabled in debug builds only. Please do not paste logs that contain API keys; the redactor should strip them, but check before posting.

**Do not report a security issue in a public issue.** Open a private security advisory on the repository instead.

---

## Finding dead code

Dead code is worth removing, and it is safe to remove more than you think. Here is how to find it, in rough order of value.

**Unused and superseded declarations.** In the Android Studio editor, a declaration that nothing references is greyed out. Right-click it and choose *Find Usages* to be sure. In IntelliJ, `Ctrl` + `Alt` + `Shift` + `F7` lists the usages of the symbol under the cursor.

**Unused imports.** The Kotlin compiler warns about these. `./gradlew compileDebugKotlin` prints them. Android Studio's *Optimize Imports* removes them in one pass.

**Parameters that are never read.** A constructor parameter or function argument that nothing uses usually means the abstraction has drifted. Either the parameter should be used, or the abstraction should be simplified.

**Commented-out code.** It is never as useful as it looks, because it stops matching the code around it. If it is worth keeping, keep it in version control, which it already is.

**Duplicated logic.** The same block in two modules is a bug waiting to happen. Usually one copy should move into a shared module.

**Unreachable branches.** A `when` branch for a sealed type that can no longer be produced, or a `null` check on a type that cannot be null.

**Abstractions with one implementation.** An interface with exactly one implementation and no test double is often just indirection. Removing it is a legitimate, welcome change. Be careful where the interface is a deliberate seam for a module boundary, such as `TerminalRuntime` or `PreviewHost`; those exist for dependency inversion and are not dead.

**When you remove something, check the tests.** A test that covers dead code is either also dead or is testing the wrong thing. If you remove the code and the test, say so in the pull request.

---

## Things that need extra care

Some parts of this project carry obligations that are easy to break by accident.

**The native PTY (`core:terminal:pty`).** This is C, built with CMake and the NDK. Android's bionic has no reliable `forkpty` and `login_tty` combination, so the PTY is opened by hand: `posix_openpt`, `grantpt`, `unlockpt`, `ptsname`, then `fork`, and in the child `setsid` and `ioctl` on `TIOCSCTTY` before `dup2` and `execve`. The parent keeps the master descriptor so the guest gets a controlling terminal, which is what makes job control, prompts and TUIs work. If you change this, test on a real device. A desktop emulator will not reproduce the behaviour.

**The vendored binaries (`core:terminal/binaries`).** These are the Termux proot runtime, shipped as `lib*.so` because Android only keeps files under `lib/<abi>/lib*.so` executable. That naming is the whole trick behind rootless Linux. The files are pinned by size and SHA-256 in `binaries.lock.json` and re-verified on every build, so a tampered binary fails the build rather than the device. Do not replace them with a local build without updating the lock file and the `fetch_termux_binaries.py` script together.

**Credential storage (`core:data/security`).** API keys are encrypted with AES-256-GCM using the Android Keystore. Never log a key, never put one in a `Bundle` or an `Intent` extra, and never put one in a crash report. If you add a field to `CredentialRecord`, keep `equals` and `hashCode` consistent, because the record holds byte arrays.

**Room migrations (`core:data`).** The database is at version 4. Any schema change needs a new migration, a new exported schema JSON, and a test that migrates from the previous version. The exported schemas in `core/data/schemas/` are the source of truth and must be committed. Use `@Upsert` rather than `@Insert(REPLACE)`: `REPLACE` deletes and re-inserts, which fires `ON DELETE CASCADE` and silently wipes sessions and credentials. There is a regression test for exactly this.

**The command classifier (`core:tools/Command.kt`).** It is a safety signal, not a security boundary, and it never executes anything. It errs toward a higher impact when it is unsure. If you add a command pattern, add a test that shows which side of the line it lands on.

**The redaction layer (`core/common/redaction`).** `Redactor` strips known-sensitive values from URLs, headers, tool arguments and throwable messages before anything is logged. If you add a way for a secret to reach a log, extend the redactor in the same change and add a test.

**The agent loop (`core:agent`).** Read-only tools run concurrently and mutating tools run in sequence behind approval. The order of results is restored to the model's call order before they are sent back. A throwing tool must become a failed result, not an exception that ends the turn.

**WebView preview (`feature:preview`).** Navigation is locked to the loopback dev server, and the only JavaScript interface exposed is a `report` function for console output. Do not expose anything else, and do not weaken the navigation lock. Remote pages must never be able to reach the bridge.

**`jniLibs.useLegacyPackaging`.** This is set to `true` on purpose. It makes native libraries extract as real files at install time, which is what keeps proot executable on `targetSdk` 36. Do not turn it off.

---

## Repository rules

A few things must not be committed. The `.gitignore` already covers most of them, but please check before opening a pull request.

**Do not commit** build output, IDE state, `local.properties`, signing keys, API keys, or device screenshots and screen recordings from ad-hoc testing.

**Do commit** the exported Room schemas in `core/data/schemas/` and the vendored binaries in `core/terminal/binaries/src/main/jniLibs/`. The build depends on both. A pull request that deletes either will fail CI.

**Do not** reformat the whole repository. A diff nobody can read is a diff nobody reviews.

**Do not** bump dependency versions as part of an unrelated change. A dependency bump is its own pull request, with a note on what changed and why.

---

## Adding a tool

Tools are the agent's verbs. A new tool is a new file in `:core:tools` plus a registration in `ToolRegistry`.

1. **Implement the `Tool` interface.**

   ```kotlin
   class MyTool : Tool {
       override val name = "my_tool"
       override val description = "One line saying what it does and when to use it."
       override val isReadOnly = true

       override fun schema(): ToolSchema = ToolSchema(
           name = name,
           description = description,
           parametersJsonSchema = """{"type":"object", ...}""",
       )

       override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
           // ...
       }
   }
   ```

2. **Set `isReadOnly` correctly.** A read-only tool may run concurrently with the other read-only tools in the same step. A tool that writes anything must be `false`, so it is sequenced and gated by the permission mode.

3. **Set `requiresApproval` on the result** if the tool mutates state and should always ask first.

4. **Register it** in `ToolRegistry`, which is constructed by injection and indexed by name.

5. **Write tests.** At minimum: a happy path, a bad-arguments path, and a path that proves the tool is safe on a missing file or an empty result.

6. **Never throw.** Return a `ToolResult` with `success = false` and a message the model can act on. An exception ends the turn.

---

## Adding a provider

`:core:providers:api` holds the contracts and must stay provider-neutral. No provider DTO may leak into it.

To add a provider, implement `AiProviderAdapter` in a new module under `:core:providers`, then bind it in that module's Hilt module. If a new implementation of `AiProviderAdapter` can exist, the binding has to be unambiguous, so think about how the app will choose between them before you add it.

`ProviderProtocol` already declares `ANTHROPIC_MESSAGES` and `GEMINI_STYLE`, but no adapter exists for either yet. Adding one is a welcome contribution.

---

## Code of conduct

Be decent to each other. Assume good faith, and assume the person you are replying to knows things you do not.

Harassment of any kind is not tolerated, and it includes offensive comments related to gender, gender identity and expression, sexual orientation, disability, mental illness, neurotype, physical appearance, body size, age, race, nationality, or religion. Unwelcome attention, intimidation, and any disruption of discussion are not acceptable.

If you experience or witness unacceptable behaviour, please report it to the maintainers through a private issue or by email. Reports will be handled discreetly.

Maintainers are responsible for clarifying the standards of acceptable behaviour and are expected to take appropriate and fair corrective action in response to any instance of unacceptable behaviour.

---

## Thank you

Whatever you end up contributing, thank you. Kern is better for it, and so is everyone who uses it.
