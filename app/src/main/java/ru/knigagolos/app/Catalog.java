package ru.knigagolos.app;

import android.content.Context;
import android.net.Uri;
import android.text.Html;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

// Search the Russian LibriVox collection at its audio host, and licensed audio on Commons.
final class Catalog {
  static List<Book> culture(Context context) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                context.getAssets().open("culture.json"), StandardCharsets.UTF_8))) {
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) data.append(line);
      JSONArray entries = new JSONArray(data.toString());
      List<Book> books = new ArrayList<>();
      for (int i = 0; i < entries.length(); i++) books.add(Book.parse(entries.getJSONObject(i)));
      return books;
    } catch (IOException | JSONException e) {
      return Collections.emptyList();
    }
  }

  static List<Book> librivox(String text) throws Exception {
    StringBuilder query =
        new StringBuilder("collection:librivoxaudio AND (language:rus OR language:Russian)");
    for (String word : text.split("[^\\p{L}\\p{N}]+")) {
      if (!word.isEmpty()) query.append(" AND ").append(word);
    }
    Uri u =
        Uri.parse("https://archive.org/advancedsearch.php")
            .buildUpon()
            .appendQueryParameter("q", query.toString())
            .appendQueryParameter("fl[]", "identifier")
            .appendQueryParameter("fl[]", "title")
            .appendQueryParameter("fl[]", "creator")
            .appendQueryParameter("rows", "12")
            .appendQueryParameter("output", "json")
            .build();
    JSONArray a = Net.getCatalog(u.toString()).getJSONObject("response").getJSONArray("docs");
    List<Book> result = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject j = a.getJSONObject(i);
      String id = j.getString("identifier");
      Object author = j.opt("creator");
      String name =
          author instanceof JSONArray ? ((JSONArray) author).optString(0) : j.optString("creator");
      result.add(
          new Book(
              "ia:" + id,
              j.getString("title"),
              name,
              "LibriVox",
              "https://archive.org/details/" + Uri.encode(id),
              Collections.emptyList()));
    }
    return result;
  }

  static Book resolve(Book book) throws Exception {
    if (!book.tracks.isEmpty()) return book;
    String id = Uri.encode(book.id.substring(3));
    JSONObject meta = Net.getCatalog("https://archive.org/metadata/" + id);
    JSONArray files = meta.getJSONArray("files");
    Map<String, JSONObject> parts = new LinkedHashMap<>();
    for (int i = 0; i < files.length(); i++) {
      JSONObject f = files.getJSONObject(i);
      String name = f.optString("name");
      if (!name.toLowerCase(Locale.ROOT).endsWith(".mp3")) continue;
      String key = f.optString("original", name).replaceFirst("(?i)(_(64|128)kb)?\\.[^.]+$", "");
      String track = f.optString("track", "");
      if (!track.isEmpty()) key = track + "|" + f.optString("disc", "1");
      JSONObject old = parts.get(key);
      if (old == null || "original".equals(f.optString("source"))) parts.put(key, f);
    }
    List<JSONObject> sorted = new ArrayList<>(parts.values());
    sorted.sort(
        Comparator.comparingInt((JSONObject j) -> number(j.optString("disc"), 1))
            .thenComparingInt(j -> number(j.optString("track"), Integer.MAX_VALUE))
            .thenComparing(j -> j.optString("name")));
    List<String> urls = new ArrayList<>();
    for (JSONObject f : sorted)
      urls.add("https://archive.org/download/" + id + "/" + Uri.encode(f.getString("name"), "/"));
    if (urls.isEmpty()) throw new IOException("Нет доступной аудиозаписи");
    return new Book(book.id, book.title, book.author, book.source, book.credit, urls);
  }

  private static int number(String s, int fallback) {
    try {
      return Integer.parseInt(s.split("/")[0]);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  static List<Book> commons(String text) throws Exception {
    String terms = text.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
    Uri u =
        Uri.parse("https://commons.wikimedia.org/w/api.php")
            .buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "6")
            .appendQueryParameter("gsrsearch", terms + " incategory:\"Audiobooks in Russian\"")
            .appendQueryParameter("gsrlimit", "12")
            .appendQueryParameter("prop", "videoinfo")
            .appendQueryParameter("viprop", "url|mime|extmetadata|derivatives")
            .appendQueryParameter(
                "viextmetadatafilter", "Artist|LicenseShortName|LicenseUrl|Attribution")
            .build();
    JSONObject response = Net.getCatalog(u.toString());
    if (response.has("error")) throw new IOException("Ошибка каталога");
    JSONObject q = response.optJSONObject("query");
    List<Book> result = new ArrayList<>();
    if (q == null || !q.has("pages")) return result;
    JSONObject pages = q.getJSONObject("pages");
    Iterator<String> keys = pages.keys();
    while (keys.hasNext()) {
      JSONObject page = pages.getJSONObject(keys.next());
      JSONArray versions = page.optJSONArray("videoinfo");
      if (versions == null || versions.length() == 0) continue;
      JSONObject f = versions.getJSONObject(0);
      if (!f.optString("mime").startsWith("audio/")
          && !f.optString("mime").equals("application/ogg")) continue;
      String title =
          page.getString("title")
              .replaceFirst("^File:(Ru-)?", "")
              .replaceFirst("\\.[^.]+$", "")
              .replace('_', ' ');
      JSONObject m = f.optJSONObject("extmetadata");
      String credit =
          String.join(
                  "\n",
                  f.optString("descriptionurl"),
                  field(m, "Artist"),
                  field(m, "Attribution"),
                  field(m, "LicenseShortName"),
                  field(m, "LicenseUrl"))
              .trim();
      result.add(
          new Book(
              "wiki:" + page.getString("pageid"),
              title,
              "",
              "Викисклад",
              credit,
              Collections.singletonList(audioUrl(f))));
    }
    return result;
  }

  private static String audioUrl(JSONObject info) throws JSONException {
    JSONArray variants = info.optJSONArray("derivatives");
    if (variants != null) {
      for (int i = 0; i < variants.length(); i++) {
        JSONObject variant = variants.getJSONObject(i);
        if ("audio/mpeg".equals(variant.optString("type"))
            && variant.optString("src").startsWith("https://")) return variant.getString("src");
      }
    }
    return info.getString("url");
  }

  private static String field(JSONObject m, String key) {
    if (m == null || m.optJSONObject(key) == null) return "";
    return Html.fromHtml(m.optJSONObject(key).optString("value"), Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .trim();
  }
}
