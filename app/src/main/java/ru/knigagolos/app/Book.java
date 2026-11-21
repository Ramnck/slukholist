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
      if (!data.contains("historyVersion")) {
        SharedPreferences.Editor edit = data.edit();
        for (Book book : all()) {
          if (data.contains("t:" + book.id)) edit.putBoolean("played:" + book.id, true);
        }
        edit.putInt("historyVersion", 1).apply();
      }
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

    List<Book> recent() {
      List<Book> result = all();
      result.removeIf(b -> !data.getBoolean("played:" + b.id, false));
      return result;
    }

    List<Book> search(String query) {
      List<Book> matches = new ArrayList<>();
      String terms = searchable(query);
      if (terms.isEmpty()) return matches;
      for (Book b : all()) {
        String label = searchable(b.label());
        if (Arrays.stream(terms.split(" ")).allMatch(label::contains)) matches.add(b);
      }
      return matches;
    }

    private String searchable(String s) {
      return s.toLowerCase(Locale.ROOT)
          .replace('ё', 'е')
          .replaceAll("[^\\p{L}\\p{N}]+", " ")
          .trim();
    }

    Book get(String id) {
      for (Book b : all()) if (b.id.equals(id)) return b;
      return null;
    }

    void put(Book book, boolean played) {
      List<Book> books = all();
      int index = 0;
      while (index < books.size() && !books.get(index).id.equals(book.id)) index++;
      if (index < books.size()) books.remove(index);
      books.add(played ? 0 : index, book);
      JSONArray a = new JSONArray();
      try {
        for (Book b : books) a.put(b.json());
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      SharedPreferences.Editor edit = data.edit().putString("shelf", a.toString());
      if (played) edit.putBoolean("played:" + book.id, true);
      edit.apply();
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
