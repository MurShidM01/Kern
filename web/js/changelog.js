/* Renders the release timeline from js/changelog/v*.js into [data-changelog]. */
(function () {
  "use strict";

  var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function anchor(version) {
    return "v" + String(version).replace(/\./g, "-");
  }

  function prettyDate(iso) {
    var parts = String(iso).split("-");
    if (parts.length !== 3) return iso;
    var month = MONTHS[parseInt(parts[1], 10) - 1] || parts[1];
    return parseInt(parts[2], 10) + " " + month + " " + parts[0];
  }

  function groupHtml(group) {
    return (
      '<div class="mt-5">' +
        '<p class="label">' + esc(group.title) + "</p>" +
        "<ul class=\"mt-2\">" +
          (group.items || []).map(function (item) {
            return "<li>" + esc(item) + "</li>";
          }).join("") +
        "</ul>" +
      "</div>"
    );
  }

  function releaseHtml(release, isLatest) {
    var tagUrl = "https://github.com/MurShidM01/Kern/releases/tag/" +
      encodeURIComponent(release.tag || "v" + release.version);

    return (
      '<article class="card mb-6 scroll-mt-24" id="' + anchor(release.version) + '">' +
        '<div class="flex flex-wrap items-center justify-between gap-3">' +
          '<div class="flex flex-wrap items-center gap-3">' +
            '<h2 class="font-display text-[26px] font-bold tracking-tight">v' +
              esc(release.version) + "</h2>" +
            '<span class="badge"><span class="badge__dot"></span>build ' + esc(release.build) + "</span>" +
            (isLatest ? '<span class="chip chip--plain">Latest</span>' : "") +
          "</div>" +
          '<time class="font-mono text-[12px] text-muted" datetime="' + esc(release.date) + '">' +
            esc(prettyDate(release.date)) + "</time>" +
        "</div>" +
        '<p class="mt-3 text-[15px] font-medium">' + esc(release.headline) + "</p>" +
        '<p class="mt-2 text-[14px] text-muted">' + esc(release.summary) + "</p>" +
        (release.groups || []).map(groupHtml).join("") +
        '<a class="btn btn--ghost btn--sm mt-6" href="' + esc(tagUrl) +
          '" target="_blank" rel="noopener">Release notes on GitHub</a>' +
      "</article>"
    );
  }

  function boot() {
    var host = document.querySelector("[data-changelog]");
    if (!host) return;

    var releases = (window.KERN_RELEASES || []).slice().sort(function (a, b) {
      return String(b.date).localeCompare(String(a.date));
    });

    if (!releases.length) {
      host.innerHTML = '<p class="text-muted">No releases are published yet.</p>';
      return;
    }

    var jump = '<nav class="mb-8 flex flex-wrap items-center gap-2" aria-label="Versions">' +
      releases.map(function (release, index) {
        return '<a class="chip" href="#' + anchor(release.version) + '">v' + esc(release.version) +
          (index === 0 ? " · latest" : "") + "</a>";
      }).join("") +
      "</nav>";

    host.innerHTML = jump + releases.map(function (release, index) {
      return releaseHtml(release, index === 0);
    }).join("");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
