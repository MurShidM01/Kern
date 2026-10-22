/* ==========================================================================
   Kern - site behaviour
   --------------------------------------------------------------------------
   Plain JavaScript, no framework. Everything is progressive: the page is
   fully readable with this file blocked, and the script only adds the
   navigation, the reveal animation, the tabs, the accordion and the
   counters on top.
   ========================================================================== */
(function () {
  "use strict";

  /* ------------------------------------------------------------------
     Mobile navigation
     ------------------------------------------------------------------ */
  function initNav() {
    var toggle = document.querySelector("[data-nav-toggle]");
    var panel = document.querySelector("[data-nav-panel]");
    if (!toggle || !panel) return;

    function setOpen(open) {
      panel.classList.toggle("hidden", !open);
      toggle.setAttribute("aria-expanded", String(open));
      toggle.querySelectorAll("svg").forEach(function (icon, i) {
        icon.classList.toggle("hide", open ? i !== 0 : i !== 1);
      });
    }

    setOpen(false);

    toggle.addEventListener("click", function () {
      setOpen(panel.classList.contains("hidden"));
    });

    panel.addEventListener("click", function (event) {
      if (event.target.closest("a")) setOpen(false);
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") setOpen(false);
    });

    window.addEventListener("resize", function () {
      if (window.innerWidth > 900) setOpen(false);
    });
  }

  /* ------------------------------------------------------------------
     Header shadow once the page is scrolled
     ------------------------------------------------------------------ */
  function initHeader() {
    var header = document.querySelector("[data-header]");
    if (!header) return;

    function update() {
      header.classList.toggle("is-stuck", window.scrollY > 12);
    }

    update();
    window.addEventListener("scroll", update, { passive: true });
  }

  /* ------------------------------------------------------------------
     Reveal on scroll
     ------------------------------------------------------------------ */
  function initReveal() {
    var items = document.querySelectorAll(".reveal");
    if (!items.length) return;

    if (!("IntersectionObserver" in window)) {
      items.forEach(function (el) {
        el.classList.add("is-in");
      });
      return;
    }

    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          var el = entry.target;
          var delay = parseInt(el.dataset.revealDelay || "0", 10);
          setTimeout(function () {
            el.classList.add("is-in");
          }, delay);
          observer.unobserve(el);
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
    );

    items.forEach(function (el) {
      observer.observe(el);
    });
  }

  /* ------------------------------------------------------------------
     Tabs
     ------------------------------------------------------------------ */
  function initTabs() {
    document.querySelectorAll("[data-tabs]").forEach(function (group) {
      var tabs = Array.prototype.slice.call(group.querySelectorAll('[role="tab"]'));

      function select(tab) {
        tabs.forEach(function (t) {
          var on = t === tab;
          t.setAttribute("aria-selected", String(on));
          t.setAttribute("tabindex", on ? "0" : "-1");
          var panel = document.getElementById(t.getAttribute("aria-controls"));
          if (panel) panel.hidden = !on;
        });
      }

      tabs.forEach(function (tab, index) {
        tab.addEventListener("click", function () {
          select(tab);
        });

        tab.addEventListener("keydown", function (event) {
          var next = null;
          if (event.key === "ArrowRight") next = tabs[(index + 1) % tabs.length];
          if (event.key === "ArrowLeft") next = tabs[(index - 1 + tabs.length) % tabs.length];
          if (event.key === "Home") next = tabs[0];
          if (event.key === "End") next = tabs[tabs.length - 1];
          if (!next) return;
          event.preventDefault();
          next.focus();
          select(next);
        });
      });

      var initial = tabs.filter(function (t) {
        return t.getAttribute("aria-selected") === "true";
      })[0] || tabs[0];
      if (initial) select(initial);
    });
  }

  /* ------------------------------------------------------------------
     Accordion
     ------------------------------------------------------------------ */
  function initAccordions() {
    document.querySelectorAll("[data-accordion]").forEach(function (group) {
      var single = group.dataset.accordion === "single";

      group.querySelectorAll(".acc__head").forEach(function (head) {
        head.addEventListener("click", function () {
          var open = head.getAttribute("aria-expanded") === "true";
          if (single) {
            group.querySelectorAll(".acc__head").forEach(function (other) {
              if (other !== head) other.setAttribute("aria-expanded", "false");
            });
          }
          head.setAttribute("aria-expanded", String(!open));
        });
      });
    });
  }

  /* ------------------------------------------------------------------
     Animated counters
     ------------------------------------------------------------------ */
  function initCounters() {
    var counters = document.querySelectorAll("[data-count]");
    if (!counters.length) return;

    function run(el) {
      var target = parseFloat(el.dataset.count);
      var decimals = (el.dataset.count.split(".")[1] || "").length;
      var suffix = el.dataset.suffix || "";
      var prefix = el.dataset.prefix || "";
      var duration = 1400;
      var start = null;

      function frame(now) {
        if (start === null) start = now;
        var p = Math.min((now - start) / duration, 1);
        var eased = 1 - Math.pow(1 - p, 3);
        el.textContent =
          prefix + (target * eased).toFixed(decimals).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + suffix;
        if (p < 1) requestAnimationFrame(frame);
      }

      requestAnimationFrame(frame);
    }

    if (!("IntersectionObserver" in window)) {
      counters.forEach(run);
      return;
    }

    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          run(entry.target);
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.4 }
    );

    counters.forEach(function (el) {
      observer.observe(el);
    });
  }

  /* ------------------------------------------------------------------
     Terminal typing effect
     ------------------------------------------------------------------ */
  function initTyping() {
    var source = document.querySelector("[data-typing]");
    if (!source) return;

    var lines = [];
    try {
      lines = JSON.parse(source.dataset.typing);
    } catch (e) {
      return;
    }
    if (!lines.length) return;

    // The output may be a separate element, or the same one that carries the data.
    var out = document.querySelector("[data-typing-out]") || source;
    if (out === source) {
      var clone = source.cloneNode(true);
      clone.removeAttribute("data-typing");
      source.replaceWith(clone);
      out = clone;
    }

    var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced) {
      out.innerHTML = lines.join("");
      return;
    }

    var lineIndex = 0;
    var charIndex = 0;

    function step() {
      if (lineIndex >= lines.length) {
        lineIndex = 0;
        charIndex = 0;
        out.innerHTML = "";
        setTimeout(step, 2200);
        return;
      }

      var line = lines[lineIndex];
      charIndex += 1;
      out.innerHTML = line.slice(0, charIndex) + '<span class="caret"></span>';

      if (charIndex >= line.length) {
        lineIndex += 1;
        charIndex = 0;
        out.innerHTML = line;
        setTimeout(step, 380);
      } else {
        setTimeout(step, 16);
      }
    }

    setTimeout(step, 500);
  }

  /* ------------------------------------------------------------------
     Copy buttons on code blocks
     ------------------------------------------------------------------ */
  function initCopy() {
    document.querySelectorAll("pre > code").forEach(function (code) {
      var pre = code.parentElement;
      if (!pre || pre.querySelector(".copy-btn")) return;

      var button = document.createElement("button");
      button.type = "button";
      button.className = "copy-btn";
      button.innerHTML =
        '<i data-lucide="copy"></i><span>Copy</span>';
      button.addEventListener("click", function () {
        var text = code.innerText;
        var done = function () {
          button.innerHTML = '<i data-lucide="check"></i><span>Copied</span>';
          if (window.lucide) window.lucide.createIcons();
          setTimeout(function () {
            button.innerHTML = '<i data-lucide="copy"></i><span>Copy</span>';
            if (window.lucide) window.lucide.createIcons();
          }, 1600);
        };

        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done).catch(function () {});
        } else {
          var area = document.createElement("textarea");
          area.value = text;
          area.style.position = "fixed";
          area.style.opacity = "0";
          document.body.appendChild(area);
          area.select();
          try {
            document.execCommand("copy");
            done();
          } catch (e) {}
          document.body.removeChild(area);
        }
      });

      pre.appendChild(button);
    });
  }

  /* ------------------------------------------------------------------
     Lucide icons
     ------------------------------------------------------------------ */
  function initIcons() {
    if (window.lucide && window.lucide.createIcons) {
      window.lucide.createIcons({
        attrs: { "stroke-width": 2, "aria-hidden": "true" }
      });
    }
  }

  /* ------------------------------------------------------------------
     Scroll progress and back to top
     ------------------------------------------------------------------ */
  function initScrollTools() {
    var bar = document.querySelector("[data-progress]");
    var top = document.querySelector("[data-totop]");

    function update() {
      var height = document.documentElement.scrollHeight - window.innerHeight;
      var progress = height > 0 ? Math.min(window.scrollY / height, 1) : 0;

      if (bar) bar.style.transform = "scaleX(" + progress + ")";
      if (top) top.classList.toggle("is-on", window.scrollY > 520);
    }

    update();
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);

    if (top) {
      top.addEventListener("click", function () {
        window.scrollTo({ top: 0, behavior: "smooth" });
      });
    }
  }

  /* ------------------------------------------------------------------
     Year stamps
     ------------------------------------------------------------------ */
  function initYear() {
    document.querySelectorAll("[data-year]").forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });
  }

  /* ------------------------------------------------------------------
     Boot
     ------------------------------------------------------------------ */
  function boot() {
    initNav();
    initHeader();
    initReveal();
    initTabs();
    initAccordions();
    initCounters();
    initTyping();
    initCopy();
    initScrollTools();
    initIcons();
    initYear();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
