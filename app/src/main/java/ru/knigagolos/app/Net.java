package ru.knigagolos.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class Net {
  static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(12000);
    c.setReadTimeout(35000);
    c.setRequestProperty("User-Agent", "KnigaVslukh/0.1 (Android audiobook player)");
    return c;
  }

  static JSONObject read(HttpURLConnection c) throws Exception {
    try {
      int code = c.getResponseCode();
      if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
          out.append(line);
          if (out.length() > 8000000) throw new IOException("Слишком большой ответ");
        }
        return new JSONObject(out.toString());
      }
    } finally {
      c.disconnect();
    }
  }

  static JSONObject get(String url) throws Exception {
    return read(open(url));
  }
}
