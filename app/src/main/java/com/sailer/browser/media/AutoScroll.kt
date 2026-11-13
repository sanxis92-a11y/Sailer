package com.sailer.browser.media

/**
 * «Bajar sola la página»: va bajando hasta el final (y pulsa los botones
 * «Cargar más / Ver más») para que las webs con scroll infinito carguen todo.
 * Mientras, [PageCollector] apunta cada foto y vídeo que aparece.
 *
 * Estado en window.__nvScroll = { running, done, steps, found, max }.
 */
object AutoScroll {

    /** Empieza a bajar; [maxSteps] pantallas como mucho. */
    fun start(maxSteps: Int, pauseMs: Int = 900): String = """
        (function() {
          var s = window.__nvScroll;
          if (s && s.running) return 'running';
          s = window.__nvScroll = { running: true, done: false, steps: 0, stable: 0, found: 0, clicks: 0, max: $maxSteps };
          $LOAD_MORE
          function tick() {
            if (!s.running) { s.done = true; return; }
            var before = document.documentElement.scrollHeight;
            if (nvLoadMore()) s.clicks++;
            window.scrollBy(0, Math.max(200, innerHeight * 0.9));
            s.steps++;
            setTimeout(function() {
              var after = document.documentElement.scrollHeight;
              var atBottom = innerHeight + scrollY >= after - 8;
              s.stable = (after === before && atBottom) ? s.stable + 1 : 0;
              s.found = (window.__nvSeen || []).length;
              // Cuatro vueltas seguidas en el fondo sin que crezca la página: ya no hay más.
              if (s.stable >= 4 || s.steps >= s.max) { s.running = false; s.done = true; return; }
              tick();
            }, $pauseMs);
          }
          tick();
          return 'started';
        })();
    """.trimIndent()

    const val STATUS = "JSON.stringify(window.__nvScroll || null)"

    const val STOP = "(function(){ if (window.__nvScroll) window.__nvScroll.running = false; })();"

    /** Pulsa un botón visible de «Cargar más» (nunca un enlace a otra página). Devuelve true si pulsó. */
    private val LOAD_MORE = """
          function nvLoadMore() {
            var re = /^\s*(load more|show more|see more|more results|view more|ver m[aá]s|cargar m[aá]s|mostrar m[aá]s|m[aá]s resultados|m[aá]s fotos|m[aá]s im[aá]genes|siguientes)\s*[.…›»]*\s*$/i;
            var els = document.querySelectorAll('button, a, [role="button"], input[type="button"]');
            for (var i = 0; i < els.length; i++) {
              var e = els[i];
              var text = (e.innerText || e.value || e.getAttribute('aria-label') || '').trim();
              if (!text || text.length > 40 || !re.test(text)) continue;
              if (e.tagName === 'A') {
                var href = e.getAttribute('href') || '';
                // Un enlace de verdad a otra página no se pulsa: eso lo hace «páginas siguientes».
                if (href && href !== '#' && href.indexOf('javascript:') !== 0) continue;
              }
              var r = e.getBoundingClientRect();
              if (r.width === 0 || r.height === 0 || e.disabled) continue;
              if (e.__nvClicked && Date.now() - e.__nvClicked < 3000) continue;
              e.__nvClicked = Date.now();
              e.click();
              return true;
            }
            return false;
          }
    """.trimIndent()
}
