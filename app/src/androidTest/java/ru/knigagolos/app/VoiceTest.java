package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VoiceTest {
  @Test
  public void lastTenMessagesAndReadingPreferencesSurviveRelaunch() throws Exception {
    var prefs =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getSharedPreferences("test-dialog", 0);
    prefs.edit().clear().commit();
    Conversation conversation = new Conversation(prefs);
    for (int i = 0; i < 14; i++)
      conversation.add(i % 2 == 0 ? "user" : "assistant", Integer.toString(i));
    conversation.updateNote("Любит исторические романы.");
    Conversation reopened = new Conversation(prefs);
    assertEquals("Любит исторические романы.", reopened.note());
    JSONObject route = Assistant.request(reopened.history(), "", null);
    JSONArray input = route.getJSONArray("input");
    assertEquals(10, input.length());
    assertEquals("4", input.getJSONObject(0).getString("content"));
    assertEquals("13", input.getJSONObject(9).getString("content"));
    assertEquals("low", route.getJSONObject("reasoning").getString("effort"));
    assertFalse(route.getJSONArray("tools").toString().contains("deepseek_web_search"));
    JSONObject web = Assistant.request(reopened.history(), "", "История радио");
    assertEquals(
        "deepseek_web_search", web.getJSONArray("tools").getJSONObject(0).getString("type"));
  }

  @Test
  public void distinguishesAccountConnectivityAndServiceErrors() {
    assertEquals(
        "На счёте DeepSeek недостаточно денег.",
        Failures.network(new Failures.Http(402, "api.deepseek.com"), false, "Ошибка каталога"));
    assertEquals(
        "Ошибка каталога",
        Failures.network(new Failures.Http(402, "archive.org"), false, "Ошибка каталога"));
    assertEquals(Failures.OFFLINE, Failures.network(new UnknownHostException(), true, ""));
    assertTrue(Failures.network(new SocketTimeoutException(), false, "").contains("долго"));
    assertTrue(
        Failures.network(new Failures.Http(401, "api.deepseek.com"), false, "").contains("ключ"));
    assertTrue(
        Failures.network(new Failures.Http(429, "api.deepseek.com"), false, "").contains("занят"));
  }

  @Test
  public void searchFromRecentFindsUnplayedLocalBookAndPlaysItAtBookmark() throws Exception {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Book.Shelf shelf = new Book.Shelf(app);
    String original = shelf.data.getString("shelf", "[]");
    boolean seeded = shelf.data.getBoolean("seeded", false);
    Book book =
        new Book(
            "test:voice",
            "Зелёная тетрадь",
            "Мария",
            "На телефоне",
            "",
            Collections.singletonList("asset:///chekhov.mp3"));
    shelf.put(book, false);
    shelf.mark(book.id, 0, 25000);
    shelf.data.edit().putBoolean("seeded", true).commit();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(a -> a.interpret("Открой библиотеку"));
      command(screen, "search", new JSONObject().put("query", "мария зеленая"));
      assertTrue(
          "The matching book must be offered before starting", awaitText(screen, "Включить?"));
      assertFalse(shelf.recent().stream().anyMatch(b -> b.id.equals(book.id)));
      screen.onActivity(a -> a.interpret("Включи эту книгу"));
      long until = SystemClock.elapsedRealtime() + 10000;
      while (shelf.position(book.id) <= 25000 && SystemClock.elapsedRealtime() < until)
        SystemClock.sleep(100);
      assertEquals(book.id, shelf.recent().get(0).id);
      assertTrue(shelf.position(book.id) > 25000);
      command(screen, "pause", new JSONObject());
    } finally {
      shelf
          .data
          .edit()
          .putString("shelf", original)
          .putBoolean("seeded", seeded)
          .remove("t:" + book.id)
          .remove("p:" + book.id)
          .remove("played:" + book.id)
          .commit();
    }
  }

  private static void command(ActivityScenario<MainActivity> screen, String name, JSONObject args)
      throws Exception {
    JSONObject result =
        Assistant.readAction(
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("type", "function_call")
                        .put("name", name)
                        .put("arguments", args.toString())));
    screen.onActivity(a -> a.act(result));
  }

  private static boolean awaitText(ActivityScenario<MainActivity> screen, String value) {
    AtomicBoolean visible = new AtomicBoolean();
    long until = SystemClock.elapsedRealtime() + 6000;
    while (!visible.get() && SystemClock.elapsedRealtime() < until) {
      screen.onActivity(
          a -> {
            ArrayList<View> found = new ArrayList<>();
            a.getWindow().getDecorView().findViewsWithText(found, value, View.FIND_VIEWS_WITH_TEXT);
            visible.set(found.stream().anyMatch(View::isShown));
          });
      SystemClock.sleep(50);
    }
    return visible.get();
  }
}
