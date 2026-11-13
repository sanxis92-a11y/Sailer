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

          // 1) Ventanas conocidas de Pinterest y el scroll bloqueado.
          var css = [
            '[data-test-id="fullPageSignupModal"]', '[data-test-id="signup"]', '[data-test-id="unauth-signup-modal"]',
            '[data-test-id="giftWrap"]', '[data-test-id="bottom-right-upsell"]', '[data-test-id="floating-app-upsell"]',
            '[data-test-id="unauthBottomBar"]', '[data-test-id="unauth-bottom-upsell"]', '[data-test-id="login-modal-default"]',
            '[data-test-id="signupModal"]', '[data-test-id="full-page-signup-close-button"]'
          ].join(',') + '{display:none!important}' +
            'html,body{overflow:auto!important;height:auto!important}';
          function addStyle() {
            if (document.getElementById('__nvNoWallCss')) return;
            var st = document.createElement('style');
            st.id = '__nvNoWallCss';
            st.textContent = css;
            (document.head || document.documentElement).appendChild(st);
          }

          // 2) Cualquier capa fija grande que pida iniciar sesión o registrarse.
          var WORDS = /(log ?in|sign ?up|iniciar sesi[oó]n|inicia sesi[oó]n|reg[ií]strate|continuar con google|continue with google|crear cuenta|create account|únete|join pinterest)/i;
          function cover(el) {
            var r = el.getBoundingClientRect();
            return r.width >= innerWidth * 0.6 && r.height >= innerHeight * 0.3;
          }
          function sweep() {
            addStyle();
            var all = document.body ? document.body.querySelectorAll('div, section, aside') : [];
            for (var i = 0; i < all.length && i < 6000; i++) {
              var el = all[i];
              var cs = getComputedStyle(el);
              if (cs.position !== 'fixed' || cs.display === 'none') continue;
              if (!cover(el)) {
                // Barras pegadas abajo con «Regístrate».
                var r = el.getBoundingClientRect();
                if (r.bottom >= innerHeight - 2 && r.height < innerHeight * 0.35 && WORDS.test(el.innerText || '') &&
                    !el.querySelector('input[type="search"]')) el.style.setProperty('display', 'none', 'important');
                continue;
              }
              var text = el.innerText || '';
              // Fondo oscuro sin texto que acompaña a la ventana, o la ventana con el aviso.
              if (WORDS.test(text) || (text.trim().length === 0 && parseFloat(cs.opacity) > 0 && cs.backgroundColor !== 'rgba(0, 0, 0, 0)')) {
                if (!el.querySelector('[data-test-id="pinWrapper"], [data-grid-item]')) el.style.setProperty('display', 'none', 'important');
              }
            }
            [document.documentElement, document.body].forEach(function(e) {
              if (!e) return;
              e.style.removeProperty('overflow');
              if (e.style.position === 'fixed') e.style.removeProperty('position');
            });
          }
          var timer = 0;
          function soon() { if (!timer) timer = setTimeout(function() { timer = 0; sweep(); }, 300); }
          function start() {
            sweep();
            new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
            setInterval(sweep, 2000);
          }
          if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
        })();
    """.trimIndent()
}
