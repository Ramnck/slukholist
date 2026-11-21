package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
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
      press(screen, "Слушать");
      waitPosition(shelf, id, 300);
      press(screen, "Вперёд 30 с");
      waitPosition(shelf, id, 30000);
      press(screen, "Пауза");
      int paused = shelf.position(id);
      press(screen, "Продолжить");
      waitPosition(shelf, id, paused + 300);
      press(screen, "Пауза");
      assertTrue(shelf.position(id) > paused);
    } finally {
      shelf
          .data
          .edit()
          .putString("shelf", previous)
          .putBoolean("seeded", seeded)
          .remove("t:" + id)
          .remove("p:" + id)
          .commit();
      wav.delete();
    }
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
              .findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
          for (View v : matches)
            if (v instanceof Button
                && text.contentEquals(((Button) v).getText())
                && v.isEnabled()) {
              pressed.set(v.performClick());
              break;
            }
        });
    assertTrue("Button unavailable: " + text, pressed.get());
  }
}
