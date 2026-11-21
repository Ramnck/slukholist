package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackTest {
  @Test
  public void localRecordingCanSeekPauseAndResume() throws Exception {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File wav = File.createTempFile("playback-test-", ".wav", app.getCacheDir());
    String id = "fixture:" + wav.getName();
    Book.Shelf shelf = new Book.Shelf(app);
    String previous = shelf.data.getString("shelf", "[]");
    boolean seeded = shelf.data.getBoolean("seeded", false);
    int audioBytes = 8000 * 2 * 60;
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header
        .putInt(0x46464952)
        .putInt(audioBytes + 36)
        .putInt(0x45564157)
        .putInt(0x20746d66)
        .putInt(16);
    header
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(8000)
        .putInt(16000)
        .putShort((short) 2)
        .putShort((short) 16);
    header.putInt(0x61746164).putInt(audioBytes);
    try (RandomAccessFile f = new RandomAccessFile(wav, "rw")) {
      f.write(header.array());
      f.setLength(44 + audioBytes);
    }
    shelf.put(
        new Book(
            id,
            "Проверка звука",
            "",
            "На телефоне",
            "",
            Collections.singletonList(Uri.fromFile(wav).toString())),
        true);
    shelf.data.edit().putBoolean("seeded", true).commit();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      press(screen, "Воспроизвести");
      waitPosition(shelf, id, 300);
      press(screen, "Вперёд на 10 секунд");
      waitPosition(shelf, id, 10000);
      assertTrue("One tap must seek ten seconds", shelf.position(id) < 20000);
      int beforeRecreation = shelf.position(id);
      screen.recreate();
      waitPosition(shelf, id, beforeRecreation + 300);
      screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      int paused = shelf.position(id);
      SystemClock.sleep(2500);
      assertEquals("Leaving the app must pause at its bookmark", paused, shelf.position(id));
      screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      press(screen, "Воспроизвести");
      waitPosition(shelf, id, paused + 300);
      press(screen, "Пауза");
      assertTrue(shelf.position(id) > paused);
    } finally {
      shelf
          .data
          .edit()
          .putString("shelf", previous)
          .putBoolean("seeded", seeded)
          .remove("played:" + id)
          .remove("t:" + id)
          .remove("p:" + id)
          .commit();
      wav.delete();
    }
  }

  @Test
  public void carouselPromotesOnlyThePlayedBookAndResumesItsBookmark() throws Exception {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Book.Shelf shelf = new Book.Shelf(app);
    String previous = shelf.data.getString("shelf", "[]");
    boolean seeded = shelf.data.getBoolean("seeded", false);
    Book older =
        new Book(
            "test:older",
            "Старая запись",
            "",
            "",
            "",
            Collections.singletonList("asset:///chekhov.mp3"));
    Book newest = new Book("test:newest", "Новая запись", "", "", "", older.tracks);
    shelf.put(older, true);
    shelf.put(newest, true);
    shelf.mark(older.id, 0, 45000);
    shelf.data.edit().putBoolean("seeded", true).commit();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      swipe(screen, 0.56f, 0.22f); // Player directly to second (older) book.
      swipe(screen, 0.25f, 0.59f); // Newer book.
      swipe(screen, 0.56f, 0.22f);
      assertEquals(newest.id, shelf.recent().get(0).id);
      press(screen, "Играть выбранную книгу");
      waitPosition(shelf, older.id, 45500);
      assertEquals(older.id, shelf.recent().get(0).id);
      swipe(screen, 0.56f, 0.22f); // Second card in the new order.
      swipe(screen, 0.25f, 0.59f); // Back to the book just played.
      int paused = shelf.position(older.id);
      press(screen, "Играть выбранную книгу");
      waitPosition(shelf, older.id, paused + 300);
      press(screen, "Пауза");
    } finally {
      var edit = shelf.data.edit().putString("shelf", previous).putBoolean("seeded", seeded);
      for (Book b : Arrays.asList(older, newest))
        edit.remove("played:" + b.id).remove("t:" + b.id).remove("p:" + b.id);
      edit.commit();
    }
  }

  private static void swipe(ActivityScenario<MainActivity> screen, float start, float end) {
    int[] size = new int[2];
    screen.onActivity(
        a -> {
          View decor = a.getWindow().getDecorView();
          size[0] = decor.getWidth();
          size[1] = decor.getHeight();
        });
    long down = SystemClock.uptimeMillis();
    for (int step = 0; step <= 16; step++) {
      int action =
          step == 0
              ? MotionEvent.ACTION_DOWN
              : step == 16 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
      float y = size[1] * (start + (end - start) * step / 16);
      MotionEvent event =
          MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, size[0] / 2f, y, 0);
      event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
      InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
      event.recycle();
      if (step < 16) SystemClock.sleep(20);
    }
    SystemClock.sleep(450);
  }

  private static void waitPosition(Book.Shelf shelf, String id, int minimum) {
    long until = SystemClock.elapsedRealtime() + 15000;
    while (SystemClock.elapsedRealtime() < until && shelf.position(id) < minimum)
      SystemClock.sleep(100);
    assertTrue(
        "Expected playback at " + minimum + " ms, got " + shelf.position(id),
        shelf.position(id) >= minimum);
  }

  private static void press(ActivityScenario<MainActivity> screen, String text) {
    AtomicBoolean pressed = new AtomicBoolean();
    screen.onActivity(
        activity -> {
          ArrayList<View> matches = new ArrayList<>();
          activity
              .getWindow()
              .getDecorView()
              .findViewsWithText(matches, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
          for (View v : matches)
            if (text.contentEquals(v.getContentDescription()) && v.isEnabled() && v.isShown()) {
              pressed.set(v.performClick());
              break;
            }
        });
    assertTrue("Button unavailable: " + text, pressed.get());
  }
}
