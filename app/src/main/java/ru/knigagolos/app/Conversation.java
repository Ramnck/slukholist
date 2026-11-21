package ru.knigagolos.app;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

// Only dialogue and a short factual reading note are persisted; stage cues are transient.
final class Conversation {
  private final SharedPreferences preferences;
  private final List<JSONObject> messages = new ArrayList<>();

  Conversation(SharedPreferences preferences) {
    this.preferences = preferences;
    try {
      JSONArray saved = new JSONArray(preferences.getString("messages", "[]"));
      for (int i = Math.max(0, saved.length() - 10); i < saved.length(); i++) {
        messages.add(saved.getJSONObject(i));
      }
    } catch (JSONException e) {
      messages.clear();
    }
  }

  void add(String role, String text) {
    try {
      messages.add(new JSONObject().put("role", role).put("content", text));
      if (messages.size() > 10) messages.remove(0);
      preferences.edit().putString("messages", new JSONArray(messages).toString()).apply();
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  List<JSONObject> history() {
    return new ArrayList<>(messages);
  }

  String note() {
    return preferences.getString("readingNote", "");
  }

  void updateNote(String note) {
    preferences
        .edit()
        .putString("readingNote", note.substring(0, Math.min(500, note.length())))
        .apply();
  }
}
