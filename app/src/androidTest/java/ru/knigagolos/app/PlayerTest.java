package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlayerTest {
  @Test
  public void shelfKeepsEditionAndChapterAfterReopening() {
    Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
    Book.Shelf shelf = new Book.Shelf(ctx);
    String id = "test:edition-1";
    Book book = new Book(id, "Рассказы", "Чехов", "Тест", "", Arrays.asList("one", "two"));
    shelf.put(book, true);
    shelf.mark(id, 1, 12345);
    Book.Shelf reopened = new Book.Shelf(ctx);
    assertEquals(id, reopened.all().get(0).id);
    assertEquals(12345, reopened.position(id));
    assertEquals(1, reopened.track(id));
    assertEquals("two", reopened.get(id).tracks.get(1));
  }

  @Test
  public void russianVoiceSynthesizesAndPlaysWithoutNetwork() throws Exception {
    var test = InstrumentationRegistry.getInstrumentation();
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Piper> engine = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    test.runOnMainSync(
        () -> {
          Piper p =
              new Piper(
                  test.getTargetContext(),
                  s -> {
                    error.set(s);
                    finished.countDown();
                  });
          engine.set(p);
          p.say("Антон Чехов. Толстый и тонкий.", finished::countDown);
        });
    try {
      assertTrue("Piper timed out", finished.await(90, TimeUnit.SECONDS));
      assertNull(error.get());
    } finally {
      test.runOnMainSync(() -> engine.get().close());
    }
  }
}
