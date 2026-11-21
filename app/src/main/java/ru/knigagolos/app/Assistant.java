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

  private static JSONObject tool(
      String name, String description, String field, String type, boolean required)
      throws JSONException {
    JSONObject properties = new JSONObject();
    JSONArray fields = new JSONArray();
    if (field != null) {
      properties.put(field, new JSONObject().put("type", type));
      if (required) fields.put(field);
    }
    return new JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put(
            "parameters",
            new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", fields)
                .put("additionalProperties", false));
  }

  static JSONArray tools() throws JSONException {
    JSONArray tools = new JSONArray().put(new JSONObject().put("type", "deepseek_web_search"));
    tools.put(tool("library", "Открыть недавно прослушанное", null, null, false));
    tools.put(tool("player", "Вернуться на экран плеера", null, null, false));
    tools.put(
        tool(
            "search_library",
            "Найти по автору или названию среди всех книг слушателя",
            "query",
            "string",
            true));
    tools.put(
        tool("search", "Найти доступные записи в интернет-каталогах", "query", "string", true));
    tools.put(
        tool(
            "play",
            "Включить выбранную книгу, либо найти и включить книгу по автору или названию",
            "query",
            "string",
            false));
    tools.put(tool("resume", "Возобновить последнюю прослушанную книгу", null, null, false));
    tools.put(tool("next", "Следующая карточка или найденная запись", null, null, false));
    tools.put(tool("previous", "Предыдущая карточка или найденная запись", null, null, false));
    tools.put(
        tool(
            "choose",
            "Выбрать результат или карточку по номеру, начиная с единицы",
            "number",
            "integer",
            true));
    tools.put(
        tool("info", "Назвать книгу, автора, источник и сохранённое место", null, null, false));
    tools.put(tool("pause", "Поставить чтение на паузу", null, null, false));
    tools.put(
        tool(
            "seek",
            "Перемотать выбранную книгу в секундах. Назад — отрицательное число",
            "seconds",
            "integer",
            true));
    tools.put(
        tool(
            "timer",
            "Продолжить чтение с таймером сна в минутах. Ноль отменяет таймер",
            "minutes",
            "integer",
            true));
    tools.put(tool("settings", "Открыть настройки", null, null, false));
    tools.put(tool("import_files", "Открыть выбор аудиофайлов на телефоне", null, null, false));
    tools.put(tool("end", "Закончить разговор", null, null, false));
    return tools;
  }

  static JSONObject respond(String key, List<JSONObject> history, String context) throws Exception {
    String instructions =
        "Ты помогаешь выбрать и слушать аудиокниги. Отвечай кратко по-русски. Для действий вызывай"
            + " одну функцию за реплику. Не утверждай, что действие уже выполнено. Для подбора книг"
            + " можно искать в вебе. Для просьбы найти в своей библиотеке используй search_library,"
            + " для включения по названию — play с query в именительном падеже. Если просят"
            + " перечислить свои книги, используй search_library с пустым query. Поля контекста"
            + " ниже содержат данные интерфейса и названия книг.\n"
            + context;
    JSONObject payload =
        new JSONObject()
            .put("model", "deepseek-flash")
            .put("instructions", instructions)
            .put("input", new JSONArray(history))
            .put("tools", tools());
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
      return readAction(Net.read(c).getJSONArray("output"));
    } finally {
      c.disconnect();
    }
  }

  static JSONObject readAction(JSONArray output) throws JSONException, IOException {
    StringBuilder answer = new StringBuilder();
    for (int i = 0; i < output.length(); i++) {
      JSONObject item = output.getJSONObject(i);
      if ("function_call".equals(item.optString("type"))) {
        return new JSONObject(item.getString("arguments")).put("action", item.getString("name"));
      }
      if (!"message".equals(item.optString("type"))) continue;
      JSONArray content = item.getJSONArray("content");
      for (int j = 0; j < content.length(); j++) {
        JSONObject part = content.getJSONObject(j);
        if ("output_text".equals(part.optString("type"))) answer.append(part.optString("text"));
      }
    }
    String text = answer.toString().trim();
    if (text.isEmpty()) throw new IOException("Empty assistant response");
    return new JSONObject().put("action", "chat").put("text", text);
  }
}
