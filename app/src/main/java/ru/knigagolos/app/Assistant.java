package ru.knigagolos.app;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Assistant {
  static String transcribe(String key, byte[] wav) throws Exception {
    HttpURLConnection c = Net.open("https://api.deepseek.com/v1/tools/asr");
    String boundary = "books-" + UUID.randomUUID();
    try {
      c.setDoOutput(true);
      c.setRequestMethod("POST");
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      try (OutputStream out = c.getOutputStream()) {
        out.write(
            ("--"
                    + boundary
                    + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      }
      return Net.read(c).getString("text").trim();
    } finally {
      c.disconnect();
    }
  }

  static JSONObject message(String role, String text) throws JSONException {
    return new JSONObject().put("role", role).put("content", text);
  }

  static JSONObject respond(String key, List<JSONObject> history, String context) throws Exception {
    String instructions =
        "Ты русскоязычный собеседник для выбора аудиокниг. Говори естественно и кратко. Помни"
            + " пожелания. Для рекомендаций можешь искать в вебе. Верни один JSON объект с полями"
            + " action, text, query, seconds, minutes. action: chat (продолжить беседу), search"
            + " (поиск доступного аудио по краткому названию или автору), play (включить"
            + " предложенную или выбранную книгу), resume (вернуться к прежней), next (следующая"
            + " предложенная), pause, seek (seconds со знаком), timer (minutes), end. Не сочиняй"
            + " ссылки или наличие книги. Когда выбран автор или книга и надо найти запись, верни"
            + " search. При ответе на конкретный вопрос о литературе верни chat с коротким ответом."
            + " При просьбе подобрать можешь предложить произведение и спросить о предпочтениях."
            + " Контекст плеера: "
            + context;
    JSONArray input = new JSONArray(history);
    JSONObject payload =
        new JSONObject()
            .put("model", "deepseek-flash")
            .put("instructions", instructions)
            .put("input", input)
            .put("tools", new JSONArray().put(new JSONObject().put("type", "deepseek_web_search")));
    HttpURLConnection c = Net.open("https://api.deepseek.com/responses");
    c.setReadTimeout(75000);
    try {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = c.getOutputStream()) {
        out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
      }
      JSONArray output = Net.read(c).getJSONArray("output");
      StringBuilder answer = new StringBuilder();
      for (int i = 0; i < output.length(); i++) {
        JSONObject item = output.optJSONObject(i);
        if (item == null || !"message".equals(item.optString("type"))) continue;
        JSONArray content = item.optJSONArray("content");
        if (content == null) continue;
        for (int j = 0; j < content.length(); j++) {
          JSONObject part = content.getJSONObject(j);
          if ("output_text".equals(part.optString("type"))) answer.append(part.optString("text"));
        }
      }
      String s = answer.toString().trim();
      int left = s.indexOf('{'), right = s.lastIndexOf('}');
      if (left >= 0 && right > left) return new JSONObject(s.substring(left, right + 1));
      if (s.isEmpty()) throw new IOException("Нет ответа от DeepSeek");
      return new JSONObject().put("action", "chat").put("text", s);
    } finally {
      c.disconnect();
    }
  }
}
