package app.tunebox;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;

/**
 * Sesión de Google para las recomendaciones personalizadas de YouTube Music.
 * Guarda solo las cookies del inicio de sesión en el almacenamiento privado de la app.
 * Las peticiones a InnerTube usan esas cookies más la cabecera SAPISIDHASH (igual que el navegador).
 */
final class GoogleSession {
  private static final String PREFS = "tb_auth";
  private static final String KEY = "cookie";
  static volatile String cookie;

  private GoogleSession() {}

  static void load(Context c) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String v = p.getString(KEY, null);
    cookie = (v == null || v.isEmpty()) ? null : v;
  }

  static void save(Context c, String value) {
    c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY, value).apply();
    cookie = value;
  }

  static void clear(Context c) {
    c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    cookie = null;
  }

  static boolean on() { return cookie != null; }

  /** Valor de SAPISID (o __Secure-3PAPISID) dentro de la cabecera Cookie. */
  static String sapisid(String cookieHeader) {
    if (cookieHeader == null) return null;
    for (String part : cookieHeader.split(";")) {
      String t = part.trim();
      if (t.startsWith("SAPISID=")) return t.substring(8);
      if (t.startsWith("__Secure-3PAPISID=")) return t.substring(18);
    }
    return null;
  }

  /** Cabecera Authorization: SAPISIDHASH <ts>_<sha1(ts SAPISID origen)>. */
  static String authorization(String cookieHeader, String origin) throws Exception {
    String sid = sapisid(cookieHeader);
    if (sid == null) return null;
    long ts = System.currentTimeMillis() / 1000;
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    byte[] d = md.digest((ts + " " + sid + " " + origin).getBytes("UTF-8"));
    StringBuilder sb = new StringBuilder();
    for (byte b : d) sb.append(String.format("%02x", b));
    return "SAPISIDHASH " + ts + "_" + sb;
  }
}
