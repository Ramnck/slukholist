package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CatalogTest {
  private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();

  @Test
  public void findsInflectedTitleWithAuthorAndSeparateParts() {
    List<Book> catalog = Catalog.culture(app);
    List<Book> books = Book.matching(catalog, "Пиковую даму Александра Пушкина");
    assertEquals(1, books.size());
    assertFalse(Book.matching(catalog, "Пушк").isEmpty());
    assertEquals(
        books.get(0).id,
        Book.matching(catalog, "Александр Пушкин Пиковая дама аудиокнига Бэкслыш").get(0).id);
    assertEquals("Пиковая дама", books.get(0).title);
    assertEquals(8, new HashSet<>(books.get(0).tracks).size());
    assertEquals(6, Book.matching(catalog, "Война и мир").get(0).tracks.size());
  }

  // Explicit opt-in, on a disposable emulator with two speech fixtures and a temporary API key.
  @Test
  public void liveRussianSpeechAddsAndPlaysTheQueenOfSpades() throws Exception {
    Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveVoice")));
    File key = new File(app.getCacheDir(), "live-key");
    File first = new File(app.getCacheDir(), "find-queen.wav");
    File second = new File(app.getCacheDir(), "play-queen.wav");
    Book.Shelf shelf = new Book.Shelf(app);
    Book book = Book.matching(Catalog.culture(app), "Пиковая дама").get(0);
    assertNull("Live run requires a fresh library", shelf.get(book.id));
    Credentials credentials = new Credentials(app);
    try {
      credentials.save(new String(Files.readAllBytes(key.toPath()), StandardCharsets.UTF_8).trim());
      assertTrue(key.delete());
      byte[][] phrases = {Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath())};
      AtomicInteger count = new AtomicInteger();
      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        screen.onActivity(
            a -> {
              assertTrue(
                  (a.getWindow().getAttributes().flags
                          & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                      != 0);
              a.voiceInput =
                  (context, active, finished) -> {
                    int index = count.getAndIncrement();
                    if (index == 0) {
                      InstrumentationRegistry.getInstrumentation()
                          .runOnMainSync(
                              () -> {
                                ArrayList<View> finish = new ArrayList<>();
                                a.getWindow()
                                    .getDecorView()
                                    .findViewsWithText(
                                        finish,
                                        "Завершить запись",
                                        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                                assertTrue(finish.get(0).performClick());
                                assertTrue(finish.get(0).performClick());
                              });
                      assertTrue(
                          "Button should finish microphone capture", finished.getAsBoolean());
                      assertTrue("Assistant should still be active", active.getAsBoolean());
                    }
                    return index < phrases.length ? phrases[index] : null;
                  };
              ArrayList<View> buttons = new ArrayList<>();
              a.getWindow()
                  .getDecorView()
                  .findViewsWithText(buttons, "Сказать", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
              assertTrue(buttons.get(0).performClick());
            });
        long until = SystemClock.elapsedRealtime() + 180000;
        String last = "";
        while (SystemClock.elapsedRealtime() < until && shelf.position(book.id) < 1500) {
          AtomicReference<String> state = new AtomicReference<>();
          screen.onActivity(a -> state.set(visibleText(a.getWindow().getDecorView())));
          if (!last.equals(state.get())) {
            last = state.get();
            android.util.Log.i("CatalogLive", last);
          }
          if (last.contains("Сказать") && shelf.get(book.id) == null && count.get() > 0) break;
          SystemClock.sleep(250);
        }
        assertTrue("No playback checkpoint. Last UI: " + last, shelf.position(book.id) >= 1500);
        assertEquals(book.id, shelf.recent().get(0).id);
        assertEquals(8, shelf.get(book.id).tracks.size());
      }
      Conversation restored =
          new Conversation(app.getSharedPreferences("conversation", Context.MODE_PRIVATE));
      assertEquals(
          2, restored.history().stream().filter(m -> m.optString("role").equals("user")).count());
      assertTrue(
          restored
              .history()
              .get(restored.history().size() - 1)
              .getString("content")
              .contains(book.title));
      try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
        reopened.onActivity(
            a -> assertTrue(visibleText(a.getWindow().getDecorView()).contains(book.title)));
      }
      File directory = new File(app.getCacheDir(), "chapters");
      long until = SystemClock.elapsedRealtime() + 5000;
      while (SimpleCache.isCacheFolderLocked(directory) && SystemClock.elapsedRealtime() < until)
        SystemClock.sleep(100);
      StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(app);
      SimpleCache cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
      try {
        long bytes = cache.getCachedBytes(book.tracks.get(0), 0, Long.MAX_VALUE);
        assertTrue("First chapter must have landed on disk", bytes > 0);
        for (int i = 1; i < book.tracks.size(); i++)
          assertFalse("Later chapter fetched early", cache.getKeys().contains(book.tracks.get(i)));
        android.util.Log.i(
            "CatalogLive",
            "Spoken requests: "
                + count.get()
                + ", cached first part: "
                + bytes
                + ", bookmark: "
                + shelf.position(book.id));
      } finally {
        cache.release();
        database.close();
      }
    } finally {
      credentials.save("");
      key.delete();
      first.delete();
      second.delete();
    }
  }

  private static String visibleText(View view) {
    if (!view.isShown()) return "";
    if (view instanceof TextView) return ((TextView) view).getText().toString();
    StringBuilder result = new StringBuilder();
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        String child = visibleText(group.getChildAt(i));
        if (!child.isEmpty()) result.append(child).append(" ");
      }
    }
    return result.toString();
  }
}
