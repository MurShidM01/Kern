package com.aliedu.kern.feature.preview

/**
 * The JavaScript shim installed into every previewed page.
 *
 * `console.log` and friends are captured natively through
 * [android.webkit.WebChromeClient.onConsoleMessage], so this script deliberately does **not** patch
 * them — re-wrapping them would double every line. It only fills the two gaps the native callback
 * misses: `unhandledrejection` (a rejected promise nobody awaited) and `error` events whose message
 * the browser does not forward.
 *
 * The guard makes the shim idempotent, which matters because it is injected both at page start and
 * again once the page finishes loading.
 */
internal object ConsoleInjection {

    /** Name of the `@JavascriptInterface` object the shim calls back into. */
    const val BRIDGE_NAME = "KernPreviewBridge"

    private const val LEVEL_ERROR = "error"

    val SCRIPT: String = """
        (function () {
          if (window.__kernHooked) { return; }
          window.__kernHooked = true;
          function report(level, message, source, line) {
            try {
              window.$BRIDGE_NAME.report(level, String(message), source || '', line || 0);
            } catch (e) { /* bridge gone: the preview was closed */ }
          }
          window.addEventListener('error', function (event) {
            var target = event && event.target;
            if (target && target !== window && target.tagName) {
              // A resource (img/script/link) failed to load. There is no stack, only the URL.
              report('$LEVEL_ERROR',
                'Failed to load ' + target.tagName.toLowerCase() + ': ' + (target.src || target.href),
                target.src || target.href || '', 0);
              return;
            }
            var error = event && event.error;
            var message = (event && event.message) || (error && error.message) || 'Uncaught error';
            var stack = error && error.stack ? '\n' + error.stack : '';
            report('$LEVEL_ERROR', message + stack, (event && event.filename) || '', (event && event.lineno) || 0);
          }, true);
          window.addEventListener('unhandledrejection', function (event) {
            var reason = event && event.reason;
            var message = reason && reason.message ? reason.message : String(reason);
            var stack = reason && reason.stack ? '\n' + reason.stack : '';
            report('$LEVEL_ERROR', 'Unhandled promise rejection: ' + message + stack, '', 0);
          });
        })();
    """.trimIndent()
}
