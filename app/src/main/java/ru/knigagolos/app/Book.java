package ru.knigagolos.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

final class Book {
  final String id, title, author, source, credit;
  final List<String> tracks;

  Book(String id, String title, String author, String source, String credit, List<String> tracks) {
    this.id = id;
    this.title = title;
    this.author = author;
    this.source = source;
    this.credit = credit;
    this.tracks = new ArrayList<>(tracks);
  }

  String label() {
    return author.trim().isEmpty() ? title : author + ". " + title;
  }

  JSONObject json() throws JSONException {
    return new JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("source", source)
        .put("credit", credit)
        .put("tracks", new JSONArray(tracks));
  }

  static Book parse(JSONObject j) throws JSONException {
    List<String> urls = new ArrayList<>();
    JSONArray a = j.getJSONArray("tracks");
    for (int i = 0; i < a.length(); i++) urls.add(a.getString(i));
    return new Book(
        j.getString("id"),
        j.getString("title"),
        j.optString("author"),
        j.optString("source"),
        j.optString("credit"),
        urls);
  }

  static class Shelf {
    final SharedPreferences data;

    Shelf(Context c) {
      data = c.getSharedPreferences("books", Context.MODE_PRIVATE);
    }

    List<Book> all() {
      List<Book> result = new ArrayList<>();
      try {
        JSONArray a = new JSONArray(data.getString("shelf", "[]"));
        for (int i = 0; i < a.length(); i++) result.add(parse(a.getJSONObject(i)));
      } catch (JSONException ignored) {
      }
      return result;
    }

    Book get(String id) {
      for (Book b : all()) if (b.id.equals(id)) return b;
      return null;
    }

    void put(Book book, boolean played) {
      List<Book> books = all();
      books.removeIf(b -> b.id.equals(book.id));
      if (played) books.add(0, book);
      else books.add(book);
      JSONArray a = new JSONArray();
      try {
        for (Book b : books) a.put(b.json());
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      data.edit().putString("shelf", a.toString()).apply();
    }

    void mark(String id, int track, int ms) {
      data.edit().putInt("t:" + id, track).putInt("p:" + id, ms).apply();
    }

    int track(String id) {
      return data.getInt("t:" + id, 0);
    }

    int position(String id) {
      return data.getInt("p:" + id, 0);
    }
  }
}
