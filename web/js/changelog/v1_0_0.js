/* Kern release notes — v1.0.0 (initial release). Loaded only by changelog.html. */
(window.KERN_RELEASES = window.KERN_RELEASES || []).push({
  version: "1.0.0",
  build: "1",
  date: "2026-10-22",
  tag: "v1.0.0+1",
  headline: "First public release: a real Linux terminal and an AI agent in one Android app.",
  summary:
    "Kern runs a full Ubuntu userland on the phone with no root and no PC, and drives it with an " +
    "agent that reads, edits and runs code in a folder you choose.",
  groups: [
    {
      title: "The Linux environment",
      items: [
        "Rootless Ubuntu 24.04 installed from a bundled rootfs through a proot shipped as a native library — no root, no adb, no PC.",
        "Native PTY plus a VT emulator: cursor addressing, colours, alternate screen and scrollback behave like a desktop terminal.",
        "apt and dpkg work inside the environment, so packages install the normal way.",
        "One-tap install, repair and reset from the setup wizard, with a capability probe that reports what the device can run."
      ]
    },
    {
      title: "The agent",
      items: [
        "29 tools covering file reads and writes, search, shell commands, workspace listing and preview control.",
        "Four permission modes — ask every time, workspace writes, full auto and read-only — with a command safety classifier in front of the shell.",
        "Streaming replies with visible reasoning, tool rows that show exactly what ran, and a per-message copy action.",
        "Any OpenAI-compatible endpoint: bring a base URL, a key and a model id. Keys live in the Android Keystore."
      ]
    },
    {
      title: "Workspaces and files",
      items: [
        "Private workspace storage, an SD-card folder, or a whole-files access mode for large projects.",
        "Built-in file browser and editor, plus a live preview for web projects.",
        "Sessions are stored locally in Room — nothing leaves the device except the model requests you configure."
      ]
    },
    {
      title: "The app",
      items: [
        "Material 3 UI in eight palettes with light, dark and system modes, and optional dynamic colour.",
        "Terminal themes that follow the app or switch to their own dark palette.",
        "Run notifications for long agent turns, and per-ABI release APKs signed with one key so upgrades install over the last version."
      ]
    }
  ]
});
