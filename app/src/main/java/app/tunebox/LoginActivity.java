package app.tunebox;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Inicio de sesión con Google dentro de la app. Cuando la página llega a music.youtube.com,
 * se leen las cookies de esa sesión y se guardan en GoogleSession. No se usa ningún servidor.
 */
public class LoginActivity extends Activity {
  private WebView wv;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    final CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    wv = new WebView(this);
    setContentView(wv);
    cm.setAcceptThirdPartyCookies(wv, true);
    WebSettings s = wv.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
    wv.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        if (url == null || !url.startsWith("https://music.youtube.com")) return;
        String c = cm.getCookie("https://music.youtube.com");
        if (c != null && (c.contains("SAPISID") || c.contains("__Secure-3PAPISID"))) {
          GoogleSession.save(LoginActivity.this, c);
          // El reproductor incrustado de YouTube usa las cookies del WebView: se borran para que no
          // lo vea como tu cuenta (eso dispara "confirma que no eres un robot"). La sesión se
          // sigue enviando solo desde InnerTube, guardada en GoogleSession.
          cm.removeAllCookies(null);
          cm.flush();
          setResult(RESULT_OK);
          finish();
        }
      }
    });
    wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F");
  }

  @Override
  public void onBackPressed() {
    if (wv != null && wv.canGoBack()) wv.goBack(); else { setResult(RESULT_CANCELED); finish(); }
  }
}
