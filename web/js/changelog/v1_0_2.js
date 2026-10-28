/* Kern release notes — v1.0.2 (build 261029). Loaded only by changelog.html. */
(window.KERN_RELEASES = window.KERN_RELEASES || []).push({
  version: "1.0.2",
  build: "261029",
  date: "2026-10-29",
  tag: "v1.0.2+261029",
  headline: "Package installs are fixed, and the agent and the app got the polish they were missing.",
  summary:
    "The headline fix is the one that mattered most: apt could not configure anything because two " +
    "files in the rootfs came out of the archive non-executable. That is solved, and this release " +
    "adds an in-app updater, a themed splash screen and a set of smaller agent and UI fixes.",
  groups: [
    {
      title: "Fixed",
      items: [
        "Ubuntu package installation. Every file extracted from the rootfs now keeps its real tar permission bits, including hard links that Android refuses to create and that were previously copied without their mode. That is what made /usr/sbin/dpkg-preconfigure and /usr/share/debconf/frontend report Permission denied and left every apt install half-configured.",
        "Pull-to-refresh. The indicator no longer stays parked on screen when a refresh finishes instantly; it is held for a short, deliberate moment so it always animates back to rest.",
        "Agent shells. A finished command now ends on a $ prompt instead of a bare newline, and the agent reuses its shell instead of opening a new one for every step.",
        "Stop now stops. Pressing stop in the composer ends the turn immediately, with no retry prompt and no leftover running command, and the next message starts cleanly instead of showing the stopped work."
      ]
    },
    {
      title: "Added",
      items: [
        "Check for updates in Settings → About Kern. It compares the running build with the latest GitHub release and offers an in-app download and install, with the release notes shown first.",
        "A themed splash screen built from the Kern mark and wordmark — no bitmap, so it matches every palette and both light and dark modes.",
        "A jump-to-latest arrow above the composer whenever the agent writes below the fold.",
        "Heart-shaped selection handles, drawn in the active palette, in the terminal and in the agent transcript. Selecting part of a message now also offers a Copy chip.",
        "Workspace memory you control: a Settings toggle to keep memory out of every prompt, and Clear memory to delete the saved notes for every workspace at once. Notes are now reference material only — the agent never resumes an old task from them unless you ask."
      ]
    },
    {
      title: "Changed",
      items: [
        "The agent stays inside the workspace. It no longer reads or writes /sdcard, /sdcard/Downloads or other device storage unless you explicitly ask it to.",
        "Leftovers from the earlier Alpine runtime are gone from the UI, the strings and the install paths; the environment is described simply as the Linux environment.",
        "Version code is now the release date (261029 for 29 October 2026) so builds sort correctly without a counter."
      ]
    }
  ]
});
