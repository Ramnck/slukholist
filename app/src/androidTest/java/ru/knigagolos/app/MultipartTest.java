package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public class MultipartTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  @Test
  public void streamsOnePartAtATimeAndAdvancesOnCompletion() throws Exception {
    Assume.assumeTrue(
        "true".equals(InstrumentationRegistry.getArguments().getString("liveMultipart")));
    Book.Shelf shelf = new Book.Shelf(context);
    Book book = Book.matching(Catalog.culture(context), "Война и мир").get(0);
    assertEquals("culture:185868", book.id);
    assertEquals(6, book.tracks.size());
    assertNull(
        "Use a disposable app profile without a prior copy of this book", shelf.get(book.id));

    JSONObject search = new JSONObject().put("action", "search").put("query", book.title);
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(activity -> activity.act(search));
      await(
          "Publisher result did not appear",
          10_000,
          () -> {
            var offered = new java.util.concurrent.atomic.AtomicBoolean();
            screen.onActivity(
                activity -> {
                  var matches = new java.util.ArrayList<android.view.View>();
                  activity
                      .getWindow()
                      .getDecorView()
                      .findViewsWithText(
                          matches, "Включить?", android.view.View.FIND_VIEWS_WITH_TEXT);
                  offered.set(matches.stream().anyMatch(android.view.View::isShown));
                });
            return offered.get();
          });
      screen.onActivity(activity -> activity.interpret("Включи эту книгу"));
      await("Part 1 did not play", 60_000, () -> shelf.position(book.id) >= 1_500);
      assertEquals(0, shelf.track(book.id));
    }
    checkCacheThroughPart(book, 0);

    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      int savedPosition = shelf.position(book.id);
      screen.onActivity(activity -> BookPlayer.start(activity, book.id));
      await("Part 1 did not resume", 60_000, () -> shelf.position(book.id) > savedPosition);
      // BookPlayer clamps a media-session seek beyond the end to the final millisecond.
      controller().getTransportControls().seekTo(Integer.MAX_VALUE);
      await(
          "Completion did not start part 2",
          60_000,
          () -> shelf.track(book.id) == 1 && shelf.position(book.id) >= 1_500);
      assertEquals(book.id, shelf.recent().get(0).id);
    }
    checkCacheThroughPart(book, 1);
    android.util.Log.i(
        "CatalogMultipart",
        "Played first two of six parts. Saved part "
            + (shelf.track(book.id) + 1)
            + " at "
            + shelf.position(book.id)
            + " ms");
  }

  private MediaController controller() {
    var automation = instrumentation.getUiAutomation();
    automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
    try {
      for (MediaController controller :
          context.getSystemService(MediaSessionManager.class).getActiveSessions(null)) {
        if (context.getPackageName().equals(controller.getPackageName())) return controller;
      }
      throw new AssertionError("No active media session for the test app");
    } finally {
      automation.dropShellPermissionIdentity();
    }
  }

  private void checkCacheThroughPart(Book book, int lastPart) {
    File directory = new File(context.getCacheDir(), "chapters");
    await(
        "Player did not release its cache",
        10_000,
        () -> !SimpleCache.isCacheFolderLocked(directory));
    var database = new StandaloneDatabaseProvider(context);
    var cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
    try {
      for (int i = 0; i < book.tracks.size(); i++) {
        long bytes = cache.getCachedBytes(book.tracks.get(i), 0, Long.MAX_VALUE);
        if (i <= lastPart) assertTrue("Part " + (i + 1) + " was not cached", bytes > 0);
        else assertEquals("Part " + (i + 1) + " was downloaded ahead of playback", 0, bytes);
      }
    } finally {
      cache.release();
      database.close();
    }
  }

  private static void await(String failure, long timeout, BooleanSupplier condition) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until)
      SystemClock.sleep(100);
    assertTrue(failure, condition.getAsBoolean());
  }
}
