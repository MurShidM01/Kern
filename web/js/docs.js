/* ==========================================================================
   Kern - documentation page behaviour
   --------------------------------------------------------------------------
   Adds a filter over the sidebar, a scroll spy that highlights the
   section currently in view, and a table of contents on narrow screens.
   The page works without this file; it only becomes easier to navigate.
   ========================================================================== */
(function () {
  "use strict";

  /* ------------------------------------------------------------------
     Sidebar filter
     ------------------------------------------------------------------ */
  function initFilter() {
    var input = document.querySelector("[data-doc-filter]");
    var links = Array.prototype.slice.call(document.querySelectorAll("[data-doc-nav] a"));
    var empty = document.querySelector("[data-doc-empty]");
    if (!input || !links.length) return;

    function apply() {
      var term = input.value.trim().toLowerCase();
      var shown = 0;

      links.forEach(function (link) {
        var text = (link.textContent || "").toLowerCase();
        var hit = !term || text.indexOf(term) !== -1;
        link.classList.toggle("hide", !hit);
        if (hit) shown += 1;
      });

      if (empty) empty.classList.toggle("hide", shown !== 0);
    }

    input.addEventListener("input", apply);
    input.addEventListener("search", apply);
    apply();
  }

  /* ------------------------------------------------------------------
     Scroll spy
     ------------------------------------------------------------------ */
  function initScrollSpy() {
    var links = Array.prototype.slice.call(document.querySelectorAll("[data-doc-nav] a"));
    if (!links.length || !("IntersectionObserver" in window)) return;

    var map = {};
    var targets = [];

    links.forEach(function (link) {
      var id = link.getAttribute("href");
      if (!id || id.charAt(0) !== "#") return;
      var section = document.querySelector(id);
      if (!section) return;
      map[id.slice(1)] = link;
      targets.push(section);
    });

    if (!targets.length) return;

    var visible = {};

    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          visible[entry.target.id] = entry.isIntersecting;
        });

        var activeId = null;
        for (var i = 0; i < targets.length; i++) {
          if (visible[targets[i].id]) {
            activeId = targets[i].id;
            break;
          }
        }

        if (!activeId) {
          // Nothing is in view: fall back to the last heading above the fold.
          var top = window.scrollY + 140;
          for (var j = 0; j < targets.length; j++) {
            if (targets[j].offsetTop <= top) activeId = targets[j].id;
          }
        }

        links.forEach(function (link) {
          link.classList.remove("is-active");
        });
        if (activeId && map[activeId]) {
          map[activeId].classList.add("is-active");
        }
      },
      { rootMargin: "-15% 0px -70% 0px", threshold: 0 }
    );

    targets.forEach(function (section) {
      observer.observe(section);
    });
  }

  /* ------------------------------------------------------------------
     Anchor offset for the sticky header
     ------------------------------------------------------------------ */
  function initAnchors() {
    document.querySelectorAll('a[href^="#"]').forEach(function (link) {
      link.addEventListener("click", function (event) {
        var id = link.getAttribute("href");
        if (!id || id === "#") return;
        var target = document.querySelector(id);
        if (!target) return;
        event.preventDefault();
        var offset = 88;
        var top = target.getBoundingClientRect().top + window.scrollY - offset;
        window.scrollTo({ top: top, behavior: "smooth" });
        history.replaceState(null, "", id);
      });
    });
  }

  /* ------------------------------------------------------------------
     Mobile drawer
     ------------------------------------------------------------------
     On a narrow screen the sidebar is a drawer. The toggle is only
     rendered for that layout, so this simply mirrors its state.
     ------------------------------------------------------------------ */
  function initDrawer() {
    var side = document.querySelector("[data-doc-side]");
    var toggle = document.querySelector("[data-doc-toggle]");
    if (!side || !toggle) return;

    function setOpen(open) {
      side.classList.toggle("is-open", open);
      toggle.setAttribute("aria-expanded", String(open));
    }

    setOpen(false);
    toggle.addEventListener("click", function () {
      setOpen(!side.classList.contains("is-open"));
    });

    // Following a link inside the drawer should close it again.
    side.querySelectorAll("a").forEach(function (link) {
      link.addEventListener("click", function () {
        if (window.innerWidth <= 1024) setOpen(false);
      });
    });

    // A wider viewport restores the permanent sidebar.
    window.addEventListener("resize", function () {
      if (window.innerWidth > 1024) setOpen(false);
    });
  }

  /* ------------------------------------------------------------------
     Boot
     ------------------------------------------------------------------ */
  function boot() {
    initDrawer();
    initFilter();
    initScrollSpy();
    initAnchors();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
