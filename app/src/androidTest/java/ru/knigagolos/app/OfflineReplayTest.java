package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class OfflineReplayTest {
  private static final String EDITION = "culture:185868";
  private static final String TITLE = "Война и мир";
  private static final String UNCACHED_EDITION = "culture:185848";
  private static final String UNCACHED_TITLE = "Медный всадник";
  private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();

  // Invoke each phase separately, preserving app data. The test runner manages connectivity.
  @Test
  public void searchAndPlayFirstPartOnline() throws Exception {
    assumeLiveReplay();
    assertFalse("An active network is required for the download", Failures.offline(app));
    Book.Shelf shelf = new Book.Shelf(app);
    assertNull("This phase requires a fresh library", shelf.get(EDITION));

    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      findAndStart(screen, "Лев Толстой, Война и мир");
      await("The book must play beyond five seconds", () -> shelf.position(EDITION) >= 5000);
      assertEquals(EDITION, shelf.recent().get(0).id);
    }

    Book saved = shelf.get(EDITION);
    assertNotNull(saved);
    assertEquals(TITLE, saved.title);
    assertEquals(6, saved.tracks.size());
    assertEquals(0, shelf.track(EDITION));

    File directory = new File(app.getCacheDir(), "chapters");
    await(
        "Closing the screen must release the playback cache",
        () -> !SimpleCache.isCacheFolderLocked(directory));
    StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(app);
    SimpleCache cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
    try {
      long downloaded = cache.getCachedBytes(saved.tracks.get(0), 0, Long.MAX_VALUE);
      assertTrue("First part must be present on disk", downloaded > 0L);
      for (String laterPart : saved.tracks.subList(1, saved.tracks.size())) {
        assertFalse(
            "A later part was fetched before it was needed: " + laterPart,
            cache.getKeys().contains(laterPart));
      }
      android.util.Log.i(
          "OfflineReplayTest",
          "Online " + EDITION + " cached=" + downloaded + " bookmarkMs=" + shelf.position(EDITION));
    } finally {
      cache.release();
      database.close();
    }
  }

  @Test
  public void resumeCachedPartOffline() {
    assumeLiveReplay();
    await("Disable Wi-Fi and mobile data before the offline phase", () -> Failures.offline(app));
    Book.Shelf shelf = new Book.Shelf(app);
    assertNotNull("Run the online phase first and preserve app data", shelf.get(EDITION));
    int savedPosition = shelf.position(EDITION);
    assertTrue("The restored bookmark must be nonzero", savedPosition >= 5000);

    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      awaitView(screen, TITLE, View.FIND_VIEWS_WITH_TEXT);
      awaitView(screen, "Воспроизвести", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
      assertEquals(
          "Opening the app alone must preserve the bookmark",
          savedPosition,
          shelf.position(EDITION));
      click(screen, "Воспроизвести");
      long startedAt = firstPlayingPosition();
      assertTrue(
          "Playback must start near the bookmark, got " + startedAt,
          startedAt >= savedPosition - 250 && startedAt <= savedPosition + 1500);
      await(
          "The cached recording must advance from the bookmark",
          () -> shelf.position(EDITION) >= savedPosition + 2500);
      assertTrue("Replay must happen while disconnected", Failures.offline(app));
      assertEquals(0, shelf.track(EDITION));
      assertEquals(EDITION, shelf.recent().get(0).id);
      android.util.Log.i(
          "OfflineReplayTest",
          "Offline "
              + EDITION
              + " bookmarkMs="
              + savedPosition
              + " startedAtMs="
              + startedAt
              + " advancedToMs="
              + shelf.position(EDITION));
    }
  }

  @Test
  public void uncachedBookFailsOfflineWithoutMovingTheBookmark() throws Exception {
    assumeLiveReplay();
    await("Disable Wi-Fi and mobile data before the offline phase", () -> Failures.offline(app));
    Book.Shelf shelf = new Book.Shelf(app);
    int savedPosition = shelf.position(EDITION);
    assertTrue("The earlier replay must have saved a bookmark", savedPosition >= 5000);
    assertNull("The other edition must be new to this library", shelf.get(UNCACHED_EDITION));

    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      findAndStart(screen, "Александр Пушкин, " + UNCACHED_TITLE);
      awaitView(screen, Failures.OFFLINE, View.FIND_VIEWS_WITH_TEXT);
      assertEquals(
          "A failed attempt must leave the recent book in place",
          EDITION,
          shelf.recent().get(0).id);
      assertEquals(savedPosition, shelf.position(EDITION));
      assertEquals(0, shelf.position(UNCACHED_EDITION));
      android.util.Log.i(
          "OfflineReplayTest",
          "Offline "
              + UNCACHED_EDITION
              + " error="
              + Failures.OFFLINE
              + " retainedBookmarkMs="
              + savedPosition);
    }
  }

  @Test
  public void sameBookPlaysAfterNetworkRecovery() throws Exception {
    assumeLiveReplay();
    await("Restore connectivity before retrying", () -> !Failures.offline(app));
    Book.Shelf shelf = new Book.Shelf(app);
    int savedPosition = shelf.position(EDITION);
    assertNotNull(
        "The earlier attempt must have saved the unplayed edition", shelf.get(UNCACHED_EDITION));
    assertEquals(EDITION, shelf.recent().get(0).id);
    assertEquals(0, shelf.position(UNCACHED_EDITION));

    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      findAndStart(screen, "Александр Пушкин, " + UNCACHED_TITLE);
      await(
          "The same recording should play after connectivity returns",
          () -> shelf.position(UNCACHED_EDITION) >= 2500);
      assertEquals(UNCACHED_EDITION, shelf.recent().get(0).id);
      assertEquals(savedPosition, shelf.position(EDITION));
      android.util.Log.i(
          "OfflineReplayTest",
          "Retry "
              + UNCACHED_EDITION
              + " advancedToMs="
              + shelf.position(UNCACHED_EDITION)
              + " retainedBookmarkMs="
              + savedPosition);
    }
  }

  private static void assumeLiveReplay() {
    Assume.assumeTrue(
        "true".equals(InstrumentationRegistry.getArguments().getString("liveReplay")));
    Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
  }

  private static void findAndStart(ActivityScenario<MainActivity> screen, String query)
      throws Exception {
    JSONObject search = new JSONObject().put("action", "search").put("query", query);
    screen.onActivity(activity -> activity.act(search));
    awaitView(screen, "Включить?", View.FIND_VIEWS_WITH_TEXT);
    click(screen, "Воспроизвести");
  }

  @android.annotation.TargetApi(29)
  private long firstPlayingPosition() {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    MediaSessionManager manager = app.getSystemService(MediaSessionManager.class);
    automation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
    try {
      AtomicLong position = new AtomicLong();
      await(
          "Android must report the resumed media session as playing",
          () -> {
            for (MediaController controller : manager.getActiveSessions(null)) {
              if (!app.getPackageName().equals(controller.getPackageName())) continue;
              PlaybackState state = controller.getPlaybackState();
              if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                position.set(state.getPosition());
                return true;
              }
            }
            return false;
          });
      return position.get();
    } finally {
      automation.dropShellPermissionIdentity();
    }
  }

  private static void click(ActivityScenario<MainActivity> screen, String description) {
    awaitView(screen, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
    screen.onActivity(
        activity ->
            assertTrue(
                findVisible(activity, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    .performClick()));
  }

  private static void awaitView(
      ActivityScenario<MainActivity> screen, String fragment, int searchFlags) {
    AtomicReference<View> visible = new AtomicReference<>();
    await(
        "The screen must show " + fragment,
        () -> {
          screen.onActivity(activity -> visible.set(findVisible(activity, fragment, searchFlags)));
          return visible.get() != null;
        });
  }

  private static View findVisible(MainActivity activity, String fragment, int searchFlags) {
    ArrayList<View> candidates = new ArrayList<>();
    activity.getWindow().getDecorView().findViewsWithText(candidates, fragment, searchFlags);
    for (View candidate : candidates) {
      if (!candidate.isShown()) continue;
      if (searchFlags == View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) {
        if (candidate.isEnabled() && fragment.contentEquals(candidate.getContentDescription()))
          return candidate;
      } else if (candidate instanceof TextView
          && ((TextView) candidate).getText().toString().contains(fragment)) {
        return candidate;
      }
    }
    return null;
  }

  private static void await(String message, BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + 60000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(200);
    }
    fail(message);
  }
}
