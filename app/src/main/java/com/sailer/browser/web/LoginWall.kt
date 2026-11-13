package com.sailer.browser.web

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Quita los avisos de «Inicia sesión / Regístrate» que tapan la página en
 * webs como Pinterest cuando navegas sin cuenta, y devuelve el scroll que
 * esos avisos bloquean. Solo esconde la ventana: no entra en tu cuenta ni
 * cambia nada en la web (si quieres iniciar sesión, puedes hacerlo igual).
 */
object LoginWall {

    /** Webs donde se aplica (y sus versiones de cada país: pinterest.es, pinterest.com.mx…). */
    private val SITES = listOf("pinterest")

    private val handlers = WeakHashMap<WebView, ScriptHandler?>()

    private val supportsDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun install(webView: WebView) {
        if (!supportsDocumentStart) return
        handlers.remove(webView)?.remove()
        handlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*"))
    }

    /** WebViews antiguos sin scripts de inicio: se pone al terminar de cargar. */
    fun applyLate(webView: WebView) {
        if (supportsDocumentStart) return
        webView.evaluateJavascript(SCRIPT, null)
    }

    private val SCRIPT = """
        (function() {
          if (window.top !== window || window.__nvNoWall) return;
          var h = location.hostname.toLowerCase();
          var sites = ${SITES.joinToString(",", "[", "]") { "'$it'" }};
          if (!sites.some(function(s) { return h === s + '.com' || h.indexOf(s + '.') === 0 || h.indexOf('.' + s + '.') >= 0; })) return;
          try { Object.defineProperty(window, '__nvNoWall', { value: true, enumerable: false }); } catch (e) {}

          // 0) Que la web no pueda frenar el dedo al deslizar: sus avisos bloquean el
          //    scroll cancelando los gestos. Con «passive» el navegador no se lo permite.
          try {
            var add = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function(type, fn, opts) {
              if ((type === 'touchmove' || type === 'touchstart' || type === 'wheel') &&
                  (this === window || this === document || this === document.documentElement || this === document.body)) {
                opts = (typeof opts === 'object' && opts) ? Object.assign({}, opts, { passive: true }) : { passive: true, capture: !!opts };
              }
              return add.call(this, type, fn, opts);
            };
          } catch (e) {}

          // 1) Ventanas conocidas de Pinterest y el scroll bloqueado.
          var css = [
            '[data-test-id="fullPageSignupModal"]', '[data-test-id="signup"]', '[data-test-id="unauth-signup-modal"]',
            '[data-test-id="giftWrap"]', '[data-test-id="bottom-right-upsell"]', '[data-test-id="floating-app-upsell"]',
            '[data-test-id="unauthBottomBar"]', '[data-test-id="unauth-bottom-upsell"]', '[data-test-id="login-modal-default"]',
            '[data-test-id="signupModal"]'
          ].join(',') + '{display:none!important}' +
            'html,body{overflow:auto!important;touch-action:auto!important;overscroll-behavior:auto!important}';
          function addStyle() {
            if (document.getElementById('__nvNoWallCss')) return;
            var st = document.createElement('style');
            st.id = '__nvNoWallCss';
            st.textContent = css;
            (document.head || document.documentElement).appendChild(st);
          }

          // 2) Capas fijas que piden iniciar sesión o registrarse. Solo se miran las
          //    capas de arriba de la página (ahí van siempre estos avisos), para no
          //    frenar el móvil revisando miles de fotos.
          var WORDS = /(log ?in|sign ?up|iniciar sesi[oó]n|inicia sesi[oó]n|reg[ií]strate|continuar con google|continue with google|crear cuenta|create account|únete|join pinterest|ver más|see more)/i;
          function candidates() {
            var out = [], level = document.body ? [document.body] : [];
            for (var depth = 0; depth < 6 && level.length && out.length < 600; depth++) {
              var next = [];
              for (var i = 0; i < level.length; i++) {
                var kids = level[i].children;
                for (var j = 0; j < kids.length && out.length < 600; j++) { out.push(kids[j]); next.push(kids[j]); }
              }
              level = next;
            }
            return out;
          }
          var hidden = false;
          function sweep() {
            addStyle();
            var list = candidates();
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
              var cs = getComputedStyle(el);
              // Fotos borrosas detrás del aviso.
              if (cs.filter && cs.filter.indexOf('blur') >= 0) el.style.setProperty('filter', 'none', 'important');
              if (cs.position !== 'fixed' || cs.display === 'none') continue;
              var r = el.getBoundingClientRect();
              var big = r.width >= innerWidth * 0.6 && r.height >= innerHeight * 0.3;
              var text = (el.innerText || '').slice(0, 2000);
              var hasPins = !!el.querySelector('[data-test-id="pinWrapper"], [data-grid-item]');
              if (hasPins || el.querySelector('input[type="search"]')) continue;
              if (big && (WORDS.test(text) || text.trim().length === 0)) {
                // El aviso, o la capa (oscura o transparente) que hay debajo y se traga los toques.
                el.style.setProperty('display', 'none', 'important');
                hidden = true;
              } else if (!big && r.bottom >= innerHeight - 2 && r.height < innerHeight * 0.35 && WORDS.test(text)) {
                el.style.setProperty('display', 'none', 'important');
              }
            }
            [document.documentElement, document.body].forEach(function(e) {
              if (!e) return;
              e.style.removeProperty('overflow');
              e.style.removeProperty('touch-action');
              if (e.style.position === 'fixed') {
                // Bloqueo de scroll con «position: fixed; top: -Npx»: volver a donde estaba.
                var y = -parseInt(e.style.top || '0', 10) || 0;
                e.style.removeProperty('position');
                e.style.removeProperty('top');
                if (y) window.scrollTo(0, y);
              }
            });
          }
          var timer = 0;
          function soon() { if (!timer) timer = setTimeout(function() { timer = 0; sweep(); }, 800); }
          function start() {
            sweep();
            new MutationObserver(soon).observe(document.body, { childList: true });
            new MutationObserver(soon).observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class'] });
            new MutationObserver(soon).observe(document.body, { attributes: true, attributeFilter: ['style', 'class'] });
            setInterval(sweep, 2500);
          }
          if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
        })();
    """.trimIndent()
}
