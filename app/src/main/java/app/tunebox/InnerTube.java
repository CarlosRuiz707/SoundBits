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
  private static JSONObject post(String endpoint, JSONObject body, String ua) throws Exception { return post(endpoint, body, ua, true); }

  private static JSONObject post(String endpoint, JSONObject body, String ua, boolean auth) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(BASE + endpoint + "?key=" + KEY + "&prettyPrint=false").openConnection();
    c.setRequestMethod("POST");
    c.setConnectTimeout(10000);
    c.setReadTimeout(20000);
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json");
    c.setRequestProperty("User-Agent", ua);
    c.setRequestProperty("Origin", "https://music.youtube.com");
    if (auth && GoogleSession.cookie != null) {
      c.setRequestProperty("Cookie", GoogleSession.cookie);
      String auth = GoogleSession.authorization(GoogleSession.cookie, "https://music.youtube.com");
      if (auth != null) c.setRequestProperty("Authorization", auth);
      c.setRequestProperty("X-Goog-AuthUser", "0");
    }
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
    // Sin cookies: con la sesión iniciada YouTube pide "confirmar que no eres un robot" a esta petición.
    JSONObject root = post("player", body, ANDROID_UA, false);
    JSONObject status = root.optJSONObject("playabilityStatus");
    if (status != null && !"OK".equals(status.optString("status"))) {
      String reason = status.optString("reason", "No reproducible");
      if (reason.toLowerCase().contains("robot") || reason.toLowerCase().contains("sign in")) {
        throw new Exception("VERIFICACION: YouTube pidió verificación. Inténtalo más tarde.");
      }
      throw new Exception(reason);
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

  /* ---------- browse: inicio, álbumes, playlists y artistas ---------- */
  private static void collect(Object node, String key, List<JSONObject> out) {
    if (node instanceof JSONObject) {
      JSONObject o = (JSONObject) node;
      if (o.has(key)) { JSONObject v = o.optJSONObject(key); if (v != null) out.add(v); }
      java.util.Iterator<String> it = o.keys();
      while (it.hasNext()) collect(o.opt(it.next()), key, out);
    } else if (node instanceof JSONArray) {
      JSONArray a = (JSONArray) node;
      for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out);
    }
  }

  private static String firstText(JSONObject textObj) {
    if (textObj == null) return "";
    JSONArray runs = textObj.optJSONArray("runs");
    if (runs != null && runs.length() > 0) return runs.optJSONObject(0).optString("text", "");
    return textObj.optString("simpleText", "");
  }

  private static String thumbOf(JSONObject renderer) {
    try {
      JSONArray t = renderer.getJSONObject("thumbnailRenderer").getJSONObject("musicThumbnailRenderer")
          .getJSONObject("thumbnail").getJSONArray("thumbnails");
      return t.getJSONObject(t.length() - 1).optString("url", "");
    } catch (Exception e) { return ""; }
  }

  private static String typeOf(String bid) {
    if (bid == null) return "album";
    if (bid.startsWith("UC")) return "artist";
    if (bid.startsWith("VL") || bid.startsWith("PL")) return "playlist";
    return "album";
  }

  /** Inicio de YouTube Music: lista de secciones con sus elementos (misma forma que usa la interfaz). */
  static JSONArray home() throws Exception {
    JSONObject body = new JSONObject().put("context", webContext()).put("browseId", "FEmusic_home");
    JSONObject root = post("browse", body, WEB_UA);
    List<JSONObject> shelves = new ArrayList<>();
    collect(root, "musicCarouselShelfRenderer", shelves);
    collect(root, "musicImmersiveCarouselShelfRenderer", shelves);
    JSONArray out = new JSONArray();
    for (JSONObject sh : shelves) {
      JSONObject header = sh.optJSONObject("header");
      JSONObject basic = header == null ? null : header.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
      String title = basic == null ? "" : firstText(basic.optJSONObject("title"));
      if (title.isEmpty()) continue;
      JSONArray items = new JSONArray();
      JSONArray contents = sh.optJSONArray("contents");
      if (contents != null) {
        for (int i = 0; i < contents.length(); i++) {
          JSONObject c = contents.optJSONObject(i);
          if (c == null) continue;
          JSONObject two = c.optJSONObject("musicTwoRowItemRenderer");
          if (two == null) continue;
          JSONObject item = parseTwoRow(two);
          if (item != null) items.put(item);
        }
      }
      if (items.length() == 0) continue;
      out.put(new JSONObject().put("title", title).put("strapline", "").put("thumb", "")
          .put("browseId", "").put("items", items));
    }
    return out;
  }

  private static JSONObject parseTwoRow(JSONObject r) throws Exception {
    String title = firstText(r.optJSONObject("title"));
    String sub = firstText(r.optJSONObject("subtitle"));
    String art = thumbOf(r);
    JSONObject nav = r.optJSONObject("navigationEndpoint");
    if (nav != null && nav.optJSONObject("watchEndpoint") != null) {
      String vid = nav.getJSONObject("watchEndpoint").optString("videoId", "");
      if (vid.isEmpty()) return null;
      return new JSONObject().put("type", "song").put("id", vid).put("title", title).put("artist", sub).put("art", art);
    }
    if (nav != null && nav.optJSONObject("browseEndpoint") != null) {
      String bid = nav.getJSONObject("browseEndpoint").optString("browseId", "");
      if (bid.isEmpty()) return null;
      String t = typeOf(bid);
      return new JSONObject().put("type", t).put("id", bid).put("title", title).put("artist", sub).put("art", art);
    }
    return null;
  }

  /** Detalle de un álbum, playlist o artista: {title, subtitle, art, tracks:[{id,title,artist,art}]}. */
  static JSONObject detail(String browseId) throws Exception {
    JSONObject body = new JSONObject().put("context", webContext()).put("browseId", browseId);
    JSONObject root = post("browse", body, WEB_UA);
    List<JSONObject> headers = new ArrayList<>();
    collect(root, "musicDetailHeaderRenderer", headers);
    if (headers.isEmpty()) collect(root, "musicImmersiveHeaderRenderer", headers);
    String title = headers.isEmpty() ? "" : firstText(headers.get(0).optJSONObject("title"));
    String subtitle = headers.isEmpty() ? "" : firstText(headers.get(0).optJSONObject("subtitle"));
    String art = headers.isEmpty() ? "" : thumbOf(headers.get(0));
    List<JSONObject> rows = new ArrayList<>();
    collect(root, "musicResponsiveListItemRenderer", rows);
    JSONArray tracks = new JSONArray();
    for (JSONObject r : rows) {
      JSONObject t = parseRow(r);
      if (t != null) tracks.put(new JSONObject().put("id", t.getString("id")).put("title", t.getString("title"))
          .put("artist", t.getString("artist")).put("art", t.getString("art")));
    }
    return new JSONObject().put("title", title).put("subtitle", subtitle).put("art", art).put("tracks", tracks);
  }
}
