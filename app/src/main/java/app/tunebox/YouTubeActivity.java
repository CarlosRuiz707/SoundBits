package app.tunebox;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;

/* YouTube móvil dentro de Tunebox: barra de búsqueda arriba (estilo Brave) y controles abajo.
   Este WebView NO tiene el puente "Android" de la app. Solo expone "TBSB", un puente mínimo de SponsorBlock
   (consultar segmentos de un ID de vídeo válido y avisar de un salto); la página no puede hacer nada más. */
public class YouTubeActivity extends Activity {
  static final String YTM_HOME = "https://music.youtube.com/";
  static final String YT_HOME = "https://m.youtube.com/";
  static final int BG = 0xFF121212, CARD = 0xFF1F1F1F, TX = 0xFFFFFFFF, MU = 0xFFB3B3B3, AC = 0xFF1ED760;

  String currentHome = YTM_HOME;
  WebView web;
  EditText bar;
  ProgressBar prog;
  FrameLayout root;
  LinearLayout page;
  View customView;
  WebChromeClient chrome;
  WebChromeClient.CustomViewCallback customCb;

  /* SponsorBlock en YouTube y YouTube Music: detecta el ID del vídeo y salta los segmentos patrocinados/sin música */
  static final String SB_JS = """
(function(){
  if(window.__tbSb)return;window.__tbSb=1;
  var vid="",segs=[],last=-1;
  window.__tbSbSet=function(id,a){
    if(id===vid){
      segs=a||[];
      last=-1;
    }
  };
  function getVid(){
    var vs=document.querySelectorAll("video");
    for(var j=0;j<vs.length;j++){
      var item=vs[j];
      if(!item.paused||item.currentTime>0||item.readyState>0||item.currentSrc||item.src){
        return item;
      }
    }
    return vs.length?vs[0]:null;
  }
  function cur(){
    try{
      var p=new URLSearchParams(location.search).get("v");
      if(p&&/^[A-Za-z0-9_-]{11}$/.test(p))return p;
      var m=location.href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
      if(m&&m[1])return m[1];
      var path=location.pathname;
      if(path.indexOf("/shorts/")===0){
        var s=path.substr(8,11);
        if(/^[A-Za-z0-9_-]{11}$/.test(s))return s;
      }
      if(path.indexOf("/watch/")===0){
        var s2=path.substr(7,11);
        if(/^[A-Za-z0-9_-]{11}$/.test(s2))return s2;
      }
      var pl=document.querySelector("ytmusic-player-bar a[href*='watch?v='], .ytp-title-link, a.yt-simple-endpoint[href*='watch?v=']");
      if(pl&&pl.href){
        var m2=pl.href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
        if(m2&&m2[1])return m2[1];
      }
      var vEl=getVid();
      if(vEl&&(vEl.currentSrc||vEl.src)){
        var m3=(vEl.currentSrc||vEl.src).match(/id=([A-Za-z0-9_-]{11})/);
        if(m3&&m3[1])return m3[1];
      }
    }catch(e){}
    return "";
  }
  setInterval(function(){
    var id=cur();
    if(id&&id!==vid){
      vid=id;
      segs=[];
      last=-1;
      try{TBSB.segments(id);}catch(e){}
    }
    var v=getVid();
    if(!v||!segs||!segs.length)return;
    var t=v.currentTime;
    for(var i=0;i<segs.length;i++){
      var x=segs[i];
      if(t>=x.start&&t<x.end&&last!==i){
        last=i;
        var target=Math.min(x.end+0.1,(v.duration&&v.duration>0?v.duration:x.end+0.1));
        v.currentTime=target;
        try{TBSB.skipped(x.category);}catch(e){}
        break;
      }
    }
  },350);
  try{
    var st=document.createElement("style");
    st.textContent="ytmusic-mealbar-promo-renderer, ytmusic-banner-renderer, .yt-upsell-dialog-renderer{display:none !important;}";
    document.head.appendChild(st);
  }catch(e){}
})();
""";

  class Sb {
    @JavascriptInterface public void segments(final String id) {
      if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
      new Thread(new Runnable() { public void run() {
        final String data = MainActivity.sbQuery(id).toString();
        runOnUiThread(new Runnable() { public void run() {
          if (web != null) web.evaluateJavascript("window.__tbSbSet&&__tbSbSet(" + JSONObject.quote(id) + "," + data + ")", null);
        } });
      } }, "SponsorBlock-yt").start();
    }
    @JavascriptInterface public void skipped(final String cat) {
      if (cat == null) return;
      runOnUiThread(new Runnable() { public void run() {
        String label = cat;
        if ("sponsor".equals(cat)) label = "patrocinio";
        else if ("selfpromo".equals(cat)) label = "autopromoción";
        else if ("interaction".equals(cat)) label = "interacción";
        else if ("music_offtopic".equals(cat)) label = "parte sin música";
        else if ("intro".equals(cat)) label = "intro";
        else if ("outro".equals(cat)) label = "final";
        else if ("preview".equals(cat)) label = "avance";
        else if ("filler".equals(cat)) label = "relleno";
        Toast.makeText(YouTubeActivity.this, "SponsorBlock: " + label + " omitido", Toast.LENGTH_SHORT).show();
      } });
    }
  }

  int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  static boolean ytHost(String h) {
    if (h == null) return false;
    h = h.toLowerCase();
    return h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com");
  }

  /* YouTube y las páginas de consentimiento/cuenta de Google se quedan dentro; lo demás abre fuera. */
  static boolean okHost(String h) {
    if (ytHost(h)) return true;
    if (h == null) return false;
    h = h.toLowerCase();
    return h.equals("google.com") || h.endsWith(".google.com");
  }

  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    PlayService.cmd("pause"); // evita dos audios a la vez

    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    root.setOnApplyWindowInsetsListener((v, ins) -> {
      page.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
          ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
      return ins;
    });

    // Barra de búsqueda
    LinearLayout top = new LinearLayout(this);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.setPadding(dp(10), dp(8), dp(10), dp(8));
    top.setBackgroundColor(BG);
    bar = new EditText(this);
    bar.setSingleLine(true);
    bar.setHint("Buscar en YouTube Music");
    bar.setHintTextColor(MU);
    bar.setTextColor(TX);
    bar.setTextSize(15);
    bar.setSelectAllOnFocus(true);
    bar.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    bar.setPadding(dp(18), dp(10), dp(18), dp(10));
    GradientDrawable pill = new GradientDrawable();
    pill.setColor(CARD);
    pill.setCornerRadius(dp(24));
    bar.setBackground(pill);
    bar.setOnEditorActionListener((v, id, ev) -> {
      if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO
          || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
        go(bar.getText().toString());
        return true;
      }
      return false;
    });
    top.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
    page.addView(top, new LinearLayout.LayoutParams(-1, -2));

    // Progreso de carga
    prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    prog.setMax(100);
    prog.setProgressTintList(ColorStateList.valueOf(AC));
    prog.setVisibility(View.INVISIBLE);
    page.addView(prog, new LinearLayout.LayoutParams(-1, dp(2)));

    // WebView sin puente JavaScript hacia la app
    web = new WebView(this);
    web.setBackgroundColor(BG);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setSupportZoom(false);
    s.setBuiltInZoomControls(false);
    web.addJavascriptInterface(new Sb(), "TBSB");
    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    cm.setAcceptThirdPartyCookies(web, true);

    web.setWebViewClient(new WebViewClient() {
      public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        if (!r.isForMainFrame()) return false;
        Uri u = r.getUrl();
        boolean https = "https".equals(u.getScheme());
        if (https && okHost(u.getHost())) return false;
        if (https) { try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {} }
        return true;
      }
      public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
        showUrl(url);
        if (url != null && ytHost(Uri.parse(url).getHost())) {
          v.evaluateJavascript(SB_JS, null);
        }
      }
      public void onPageFinished(WebView v, String url) {
        Uri u = Uri.parse(url == null ? "" : url);
        if ("https".equals(u.getScheme()) && ytHost(u.getHost())) v.evaluateJavascript(SB_JS, null);
      }
    });
    chrome = new WebChromeClient() {
      public void onProgressChanged(WebView v, int p) {
        prog.setProgress(p);
        prog.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
      }
      public void onShowCustomView(View view, CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = view; customCb = cb;
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        page.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
      }
      public void onHideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        page.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customCb != null) { customCb.onCustomViewHidden(); customCb = null; }
      }
    };
    web.setWebChromeClient(chrome);
    page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

    // Controles inferiores
    LinearLayout nav = new LinearLayout(this);
    nav.setOrientation(LinearLayout.HORIZONTAL);
    nav.setBackgroundColor(BG);
    nav.addView(btn("Atrás", v -> { if (web.canGoBack()) web.goBack(); }));
    nav.addView(btn("Adelante", v -> { if (web.canGoForward()) web.goForward(); }));
    nav.addView(btn("Recargar", v -> web.reload()));
    nav.addView(btn("YT Music", v -> {
      currentHome = YTM_HOME;
      bar.setHint("Buscar en YouTube Music");
      web.loadUrl(currentHome);
    }));
    nav.addView(btn("YouTube", v -> {
      currentHome = YT_HOME;
      bar.setHint("Buscar en YouTube");
      web.loadUrl(currentHome);
    }));
    nav.addView(btn("Tunebox", v -> finish()));
    page.addView(nav, new LinearLayout.LayoutParams(-1, -2));

    setContentView(root);

    String mode = getIntent() == null ? null : getIntent().getStringExtra("mode");
    if ("youtube".equalsIgnoreCase(mode)) {
      currentHome = YT_HOME;
      bar.setHint("Buscar en YouTube");
    } else {
      currentHome = YTM_HOME;
      bar.setHint("Buscar en YouTube Music");
    }

    if (saved != null) {
      web.restoreState(saved);
    } else {
      String explicitUrl = getIntent() == null ? null : getIntent().getStringExtra("url");
      String q = getIntent() == null ? null : getIntent().getStringExtra("q");
      if (explicitUrl != null && !explicitUrl.trim().isEmpty()) {
        web.loadUrl(explicitUrl);
      } else if (q != null && !q.trim().isEmpty()) {
        go(q);
      } else {
        web.loadUrl(currentHome);
      }
    }
  }

  TextView btn(String label, View.OnClickListener l) {
    TextView t = new TextView(this);
    t.setText(label);
    t.setTextColor(MU);
    t.setTextSize(13);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, dp(14), 0, dp(14));
    t.setOnClickListener(l);
    t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
    return t;
  }

  /* Texto → búsqueda en YouTube Music o YouTube; si es un enlace, se abre tal cual. */
  void go(String input) {
    String t = input == null ? "" : input.trim();
    if (t.isEmpty()) return;
    String url = null;
    if (!t.contains(" ") && t.contains(".")) {
      Uri u = Uri.parse(t.contains("://") ? t : "https://" + t);
      if (ytHost(u.getHost())) url = u.buildUpon().scheme("https").build().toString();
    }
    if (url == null) {
      try {
        if (currentHome.contains("music.youtube.com")) {
          url = YTM_HOME + "search?q=" + URLEncoder.encode(t, "UTF-8");
        } else {
          url = YT_HOME + "results?search_query=" + URLEncoder.encode(t, "UTF-8");
        }
      } catch (Exception e) { return; }
    }
    web.loadUrl(url);
    bar.clearFocus();
    InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (im != null) im.hideSoftInputFromWindow(bar.getWindowToken(), 0);
  }

  void showUrl(String url) {
    if (bar == null || bar.hasFocus() || url == null) return;
    Uri u = Uri.parse(url);
    String q = u.getQueryParameter("q");
    if (q == null) q = u.getQueryParameter("search_query");
    bar.setText(q != null ? q : (u.getHost() == null ? "" : u.getHost()));
  }

  public void onBackPressed() {
    if (customView != null) { chrome.onHideCustomView(); return; }
    if (web.canGoBack()) web.goBack(); else finish();
  }

  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (web != null) web.saveState(out);
  }

  protected void onDestroy() {
    if (web != null) { root.removeView(web); web.destroy(); web = null; }
    super.onDestroy();
  }
}
