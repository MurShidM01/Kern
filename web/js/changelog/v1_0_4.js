/* Kern release notes — v1.0.4 (build 261030). Loaded only by changelog.html. */
(window.KERN_RELEASES = window.KERN_RELEASES || []).push({
  version: "1.0.4",
  build: "261030",
  date: "2026-10-30",
  tag: "v1.0.4+261030",
  headline: "Nothing kills your installs any more — and the agent finally shows its work.",
  summary:
    "The command timeout is gone, and with it the half-configured packages it used to leave behind. Projects are now real folders inside the Linux environment, long work runs in the background, and every file change is shown as a diff you can read before and after it is applied.",
  groups: [
    {
      title: "Fixed",
      items: [
        "Commands are no longer killed on a timeout. Every timeout was wrong for somebody — sixty seconds killed a cold install, ten minutes killed a slow one — and the executor answered by killing the whole process group, which is what left dpkg half-configured and made every later install fail. A command now runs until it exits; the only thing that ends it early is you stopping the turn.",
        "Stop means stopped. Cancelling a turn killed the coroutine but left the tool row marked as running in the database, so the transcript kept a spinner — and the composer kept offering Stop — long after the work had ended, and the same spinner came back on every later visit to the session. Stopping now closes the row and labels it Stopped, and reopening a session closes anything a crash left behind.",
        "Long output keeps both ends. Results used to be cut to the first four thousand characters, so the error line a failing install prints at the end was the first thing thrown away. The agent now sees the head and the tail, with a marker naming what was dropped.",
        "git_status and git_diff actually run. Both tools described a command and returned it, but nothing executed it, so the agent reviewed changes from a result that never existed.",
        "Auto edit no longer runs shell commands. It is described as \"apply file edits automatically; ask for anything else\", but the gate only looked at the impact, so npm install, a commit or a formatter ran unprompted in that mode. A shell command always asks unless you are in Full access.",
        "The destructive-command check is case-insensitive. Patterns were written with a literal -R while the command was lower-cased first, so rm -R, chmod -R 777 / and chown -R were classified as ordinary changes and skipped the approval a destructive command always needs.",
        "Commands stay in their project inside the Linux environment. The workspace is bound at /workspace (or /sdcard/<folder>), so a cd to the host path failed silently and every later command ran wherever the shell happened to be. The guest path is now resolved properly, and a path the guest cannot see says so instead of pretending.",
        "Dev servers are honest. A crashed server is detected even though its shell is still alive, a port that is already taken is refused before anything starts, and cancelling during startup closes the session instead of leaking it.",
        "A permission change now applies to the turn that is running. Switching to Full access used to take effect only in the next chat; the agent re-reads the mode at every approval gate, and a request already waiting is released when the new mode would have allowed it."
      ]
    },
    {
      title: "Added",
      items: [
        "Background jobs. Anything slow but finite — apt or npm install, a cold build, a dependency download — can run in the background with background_start, and be read with background_status, background_output, background_wait and background_stop. The job keeps its own shell and its own output, so you can keep working while it finishes.",
        "Create a project from Home. The empty state offers Create a project next to Open an existing folder, and every project is a real directory named after itself: first_app, java_apps, and so on. Inside the Linux environment they are all siblings under /workspace, so ls /workspace lists your projects the same way Home does.",
        "Pick a model instead of typing one. Tapping Model ID in the provider editor fetches the catalogue from {BASE_URL}/models with the key you just entered, filters as you type, and still lets you use any id you type yourself.",
        "A diff you can read, twice. The approval sheet shows each changed file as green added lines and red removed lines, and the transcript row for a file tool now shows the same diff instead of the tool-call JSON — so the record of a change is the change itself."
      ]
    },
    {
      title: "Changed",
      items: [
        "Provider setup reads in the order you fill it: base URL, then API key, then model. Model ID moved below the credential it needs, because the catalogue cannot be fetched without it.",
        "The waiting indicator is three floating dots instead of a spinner claiming the model is thinking. The phase label still says what is actually happening.",
        "Reasoning is one card. The header and the reasoning text now share the same surface, so the whole block reads as reasoning rather than a label followed by a stray paragraph.",
        "Version code is the release date: 261030 means 30 October 2026."
      ]
    }
  ]
});
