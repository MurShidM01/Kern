/* Kern release notes — v1.0.6 (build 20261101). Loaded only by changelog.html. */
(window.KERN_RELEASES = window.KERN_RELEASES || []).push({
  version: "1.0.6",
  build: "20261101",
  date: "2026-11-01",
  tag: "v1.0.6+20261101",
  headline: "The tool row now ends when the command ends.",
  summary:
    "A command could finish, print its prompt and still leave the agent waiting for it forever. The exit marker was being swallowed by anything that reads input, a shell left busy by an earlier command was being typed into, and a shell killed by Stop was being reused. All three are fixed, and background jobs are now tracked in the transcript exactly like commands.",
  groups: [
    {
      title: "Fixed",
      items: [
        "A finished command can no longer leave a tool row running. The exit marker used to be typed on the line after the command, so anything that reads stdin — apt, debconf, a pager, a plain read — swallowed it. The command then finished, the prompt came back, and with no timeout the agent waited for an exit status that had already been eaten. The command now travels base64-encoded into a file the shell runs, and the marker is printed on the same input line, where nothing can consume it.",
        "A shell that is busy is replaced instead of written into. If an earlier command is still sitting at a prompt — apt asking a question, a read waiting on input — everything typed afterwards is queued or swallowed and the new command never starts. The script prints a start marker before it runs anything; if that marker does not appear within eight seconds the shell is judged unresponsive, closed, and the command is retried once in a fresh one.",
        "A dead shell is never reused. Stop used to kill the coroutine but leave the session behind, and writes to a dead pty succeed silently while nothing is ever printed, so the next command waited for a sentinel that could not come. Liveness is now checked before a session is reused, and every run re-verifies it while it waits.",
        "Closing a session escalates to SIGKILL again. The guard compared the exit code before the kill was scheduled, so a session that ignored SIGTERM was never force-closed and kept its process group alive.",
        "Two commands can no longer share one shell. The agent, the package manager and the inline tools all reach the same executor, and a second command typed into a shell that is still running the first interleaves both outputs and lets either one consume the other's exit marker. A shell in use is claimed for the duration of its run.",
        "The echo of the script no longer leaks into the output. Stripping it by matching the command text failed on any long line, because the terminal re-wraps what it echoes. The marker is now built inside the shell from a seed, so the echo never contains it, and everything before it is dropped without matching a single character of the command.",
        "A stream that ends early now ends the run. If the output collector stops — the session was closed by an environment install, the shell was reaped — the run used to wait on a collector that had already completed. It now reports that the transport was lost instead of hanging.",
        "The script itself is no longer part of what a command prints. The shell echoes every line it reads, and it ran the first line — the one that prints the start marker — before echoing the rest, so the marker landed in the middle of the echo and the base64 lines after it were kept as output. The echo is now switched off before the second line is read, and back on when the command finishes.",
        "Only one task runs at a time. Sending a second message while a turn was still working started a second turn on the same session and the same shell; the two interleaved their output and Stop could only cancel the newer one. A second message is now refused with an explanation until the first finishes or is stopped.",
        "Opening another chat no longer shows the first chat's answer. A turn keeps running when you leave its transcript — that is deliberate — but its streamed text was painted into whatever session you opened next. Each turn now only draws in its own session, and coming back restores its live state.",
        "Leaving the editor with unsaved edits asks first. The back arrow and the system back gesture both discarded them without a word.",
        "Removing a project says what it does. The dialog promised that files on disk were never deleted, while an app-managed project was deleted along with its folder. The wording now matches the behaviour: an app-managed project is removed with its files, a folder you picked is only unlinked from Kern."
      ]
    },
    {
      title: "Added",
      items: [
        "Background jobs are tracked in the chat the way commands are. The tool row that started a job keeps the job's own state instead of the call that returned immediately: a live timer while it runs, a running indicator that follows the job rather than a green tick, live output as the command prints it, and the exit code and duration when it ends. A job started in an earlier app session says so instead of pretending to run.",
        "Live job output publishes on a trailing edge. Output used to be republished on a fixed interval, so the last thing a slow command printed could stay invisible until the next line arrived — for an install, minutes later. The newest text is now published as soon as the interval closes."
      ]
    },
    {
      title: "Changed",
      items: [
        "A command's output is still never truncated by a timeout. A long install runs until it exits; the only thing that ends it early is stopping the turn, and that closes the shell it was running in.",
        "Version code is the release date in YYYYMMDD: 20261101 means 1 November 2026."
      ]
    }
  ]
});
