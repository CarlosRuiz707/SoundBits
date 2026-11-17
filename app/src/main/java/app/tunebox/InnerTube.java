package app.tunebox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente nativo de YouTube Music (innertube), con la misma idea que el módulo innertube de Metrolist:
 *  - search(): búsqueda de canciones con el cliente web de YouTube Music (WEB_REMIX).
 *  - streamUrl(): URL directa del audio con el cliente de Android de YouTube Music (sin cifrado de firma).
 * Todas las llamadas son bloqueantes: úsalas siempre fuera del hilo principal.
 */
final class InnerTube {
  private static final String KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
  private static final String BASE = "https://music.youtube.com/youtubei/v1/";
  private static final String SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
  private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
  private static final String ANDROID_UA = "com.google.android.apps.youtube.music/7.11.50 (Linux; U; Android 11) gzip";

  private InnerTube() {}

  /* ---------- transporte ---------- */
  private static JSONObject post(String endpoint, JSONObject body, String ua) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(BASE + endpoint + "?key=" + KEY + "&prettyPrint=false").openConnection();
    c.setRequestMethod("POST");
    c.setConnectTimeout(10000);
    c.setReadTimeout(20000);
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json");
    c.setRequestProperty("User-Agent", ua);
    c.setRequestProperty("Origin", "https://music.youtube.com");
    byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
    try (OutputStream os = c.getOutputStream()) { os.write(data); }
    int code = c.getResponseCode();
    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
    StringBuilder sb = new StringBuilder();
    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
    }
    if (code >= 400) throw new Exception("YouTube respondió " + code);
    return new JSONObject(sb.toString());
  }

  private static JSONObject webContext() throws Exception {
    JSONObject client = new JSONObject()
        .put("clientName", "WEB_REMIX")
        .put("clientVersion", "1.20250310.01.00")
        .put("hl", "es")
        .put("gl", "MX");
    return new JSONObject().put("client", client);
  }

  private static JSONObject androidContext() throws Exception {
    JSONObject client = new JSONObject()
        .put("clientName", "ANDROID_MUSIC")
        .put("clientVersion", "7.11.50")
        .put("androidSdkVersion", 30)
        .put("osName", "Android")
        .put("osVersion", "11")
        .put("hl", "es")
        .put("gl", "MX");
    return new JSONObject().put("client", client);
  }

  /* ---------- búsqueda ---------- */
  /** Devuelve una lista de {id,title,artist,art} como la que usa la interfaz. */
  static JSONArray search(String query) throws Exception {
    JSONObject body = new JSONObject()
        .put("context", webContext())
        .put("query", query)
        .put("params", SONGS_FILTER);
    JSONObject root = post("search", body, WEB_UA);
    JSONArray out = new JSONArray();
    JSONArray shelves = root.optJSONObject("contents") == null ? null : findShelves(root);
    if (shelves == null) return out;
    for (int s = 0; s < shelves.length(); s++) {
      JSONArray items = shelves.optJSONObject(s).optJSONArray("contents");
      if (items == null) continue;
      for (int i = 0; i < items.length(); i++) {
        JSONObject row = items.optJSONObject(i);
        if (row == null || !row.has("musicResponsiveListItemRenderer")) continue;
        JSONObject item = parseRow(row.getJSONObject("musicResponsiveListItemRenderer"));
        if (item != null) out.put(item);
      }
    }
    return out;
  }

  private static JSONArray findShelves(JSONObject root) {
    try {
      JSONArray tabs = root.getJSONObject("contents").getJSONObject("tabbedSearchResultsRenderer").getJSONArray("tabs");
      JSONArray sections = tabs.getJSONObject(0).getJSONObject("tabRenderer").getJSONObject("content")
          .getJSONObject("sectionListRenderer").getJSONArray("contents");
      JSONArray shelves = new JSONArray();
      for (int i = 0; i < sections.length(); i++) {
        JSONObject sh = sections.getJSONObject(i).optJSONObject("musicShelfRenderer");
        if (sh != null) shelves.put(sh);
      }
      return shelves;
    } catch (Exception e) {
      return null;
    }
  }

  private static JSONObject parseRow(JSONObject r) throws Exception {
    String videoId = null;
    JSONObject play = r.optJSONObject("overlay");
    if (play != null) {
      videoId = play.optJSONObject("musicItemThumbnailOverlayRenderer") == null ? null :
          play.getJSONObject("musicItemThumbnailOverlayRenderer").getJSONObject("content")
              .getJSONObject("musicPlayButtonRenderer").optJSONObject("playNavigationEndpoint") == null ? null :
          play.getJSONObject("musicItemThumbnailOverlayRenderer").getJSONObject("content")
              .getJSONObject("musicPlayButtonRenderer").getJSONObject("playNavigationEndpoint")
              .getJSONObject("watchEndpoint").optString("videoId", null);
    }
    if (videoId == null) return null;
    JSONArray cols = r.getJSONArray("flexColumns");
    String title = runsText(cols.getJSONObject(0));
    String artist = cols.length() > 1 ? runsText(cols.getJSONObject(1)) : "";
    String art = "";
    JSONArray thumbs = r.optJSONObject("thumbnail") == null ? null :
        r.getJSONObject("thumbnail").getJSONObject("musicThumbnailRenderer").getJSONObject("thumbnail").optJSONArray("thumbnails");
    if (thumbs != null && thumbs.length() > 0) art = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "");
    return new JSONObject()
        .put("id", videoId)
        .put("title", title)
        .put("artist", artist)
        .put("art", art);
  }

  private static String runsText(JSONObject col) {
    try {
      JSONArray runs = col.getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text").getJSONArray("runs");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < runs.length(); i++) {
        JSONObject run = runs.getJSONObject(i);
        String t = run.optString("text", "");
        if (t.equals(" • ") || t.equals(" & ")) { sb.append(t.equals(" & ") ? " & " : ""); continue; }
        sb.append(t);
      }
      return sb.toString().trim();
    } catch (Exception e) {
      return "";
    }
  }

  /* ---------- URL directa del audio ---------- */
  /** Devuelve {url, dur} del mejor audio disponible, o lanza excepción si YouTube no entrega una URL directa. */
  static JSONObject streamUrl(String videoId) throws Exception {
    JSONObject body = new JSONObject()
        .put("context", androidContext())
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true);
    JSONObject root = post("player", body, ANDROID_UA);
    JSONObject status = root.optJSONObject("playabilityStatus");
    if (status != null && !"OK".equals(status.optString("status"))) {
      throw new Exception(status.optString("reason", "No reproducible"));
    }
    JSONObject sd = root.optJSONObject("streamingData");
    if (sd == null) throw new Exception("Sin datos de streaming");
    JSONArray formats = sd.optJSONArray("adaptiveFormats");
    if (formats == null) throw new Exception("Sin formatos de audio");
    String bestUrl = null;
    long bestBitrate = -1;
    for (int i = 0; i < formats.length(); i++) {
      JSONObject f = formats.getJSONObject(i);
      String mime = f.optString("mimeType", "");
      String url = f.optString("url", "");
      if (!mime.startsWith("audio/") || url.isEmpty()) continue;   // sin signatureCipher: solo URL directa
      long bitrate = f.optLong("bitrate", 0);
      if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = url; }
    }
    if (bestUrl == null) throw new Exception("YouTube no entregó una URL directa de audio");
    long dur = 0;
    try { dur = Long.parseLong(root.optJSONObject("videoDetails").optString("lengthSeconds", "0")); } catch (Exception ignored) {}
    return new JSONObject().put("url", bestUrl).put("dur", dur);
  }
}
