/* ==========================================================================
   Kern - theme controller
   --------------------------------------------------------------------------
   The theme is applied to <html> as data-theme, so a single attribute
   swap repaints every custom property in the stylesheet. The choice is
   stored in localStorage and applied before first paint by the inline
   snippet in each page's <head>, which is what stops a light flash on a
   dark-mode load.
   ========================================================================== */
(function () {
  "use strict";

  var KEY = "kern-theme";
  var root = document.documentElement;

  function systemPrefersDark() {
    return (
      window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
    );
  }

  function stored() {
    try {
      return localStorage.getItem(KEY);
    } catch (e) {
      return null;
    }
  }

  function store(value) {
    try {
      localStorage.setItem(KEY, value);
    } catch (e) {
      /* Private browsing: the theme still applies for this page view. */
    }
  }

  function resolve() {
    var saved = stored();
    if (saved === "light" || saved === "dark") return saved;
    return systemPrefersDark() ? "dark" : "light";
  }

  function apply(theme) {
    root.setAttribute("data-theme", theme);
    root.style.colorScheme = theme;

    // The icon swap itself is done in CSS, keyed off data-theme, so it is
    // right before this script runs. Only the accessible state is set here.
    document.querySelectorAll("[data-theme-toggle]").forEach(function (btn) {
      var text = btn.querySelector("[data-theme-text]");
      if (text) text.textContent = theme === "dark" ? "Light" : "Dark";
      btn.setAttribute(
        "aria-label",
        theme === "dark" ? "Switch to the light theme" : "Switch to the dark theme"
      );
      btn.setAttribute("aria-pressed", String(theme === "dark"));
    });

    document
      .querySelectorAll('meta[name="theme-color"]')
      .forEach(function (meta) {
        meta.setAttribute("content", theme === "dark" ? "#09090e" : "#f4f3ef");
      });
  }

  function current() {
    return root.getAttribute("data-theme") || "light";
  }

  function toggle() {
    var next = current() === "dark" ? "light" : "dark";
    store(next);
    apply(next);
  }

  // Expose the API before DOMContentLoaded so the head snippet and the
  // page scripts can both reach it.
  window.KernTheme = {
    apply: apply,
    toggle: toggle,
    current: current,
    resolve: resolve,
    key: KEY
  };

  apply(resolve());

  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll("[data-theme-toggle]").forEach(function (btn) {
      btn.addEventListener("click", toggle);
    });
  });

  // Follow the system preference only while the visitor has not chosen.
  if (window.matchMedia) {
    var query = window.matchMedia("(prefers-color-scheme: dark)");
    var onChange = function () {
      if (!stored()) apply(resolve());
    };
    if (query.addEventListener) query.addEventListener("change", onChange);
    else if (query.addListener) query.addListener(onChange);
  }
})();
