package ru.knigagolos.app;

import static org.junit.Assert.*;

import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CatalogAvailabilityTest {
  private static final Book DUBROVSKY =
      new Book(
          "ia:dubrovsky_1411_librivox",
          "Дубровский",
          "Александр Пушкин",
          "LibriVox",
          "https://archive.org/details/dubrovsky_1411_librivox",
          Collections.emptyList());

  private final ExecutorService jobs = Executors.newCachedThreadPool();
  private final BlockingQueue<CatalogSearch.Result> results = new LinkedBlockingQueue<>();
  private final AtomicBoolean active = new AtomicBoolean(true);

  @After
  public void cleanup() {
    active.set(false);
    jobs.shutdownNow();
  }

  @Test
  public void showsAvailableBookWhileSecondCatalogIsStalled() throws Exception {
    CountDownLatch allowSecondCatalog = new CountDownLatch(1);
    try {
      start(
          Collections.emptySet(),
          () -> Collections.singletonList(DUBROVSKY),
          () -> {
            allowSecondCatalog.await();
            return Collections.emptyList();
          });
      CatalogSearch.Result result = resultWithin(3);
      assertEquals(CatalogSearch.Availability.PARTIAL, result.availability);
      assertEquals(DUBROVSKY.id, result.books.get(0).id);
    } finally {
      allowSecondCatalog.countDown();
    }
    assertNull(results.poll(1, TimeUnit.SECONDS));
  }

  @Test
  public void distinguishesPartialFailureFromEmptyAndTotalFailure() throws Exception {
    start(Collections.emptySet(), Collections::emptyList, this::unavailable);
    CatalogSearch.Result partial = resultWithin(3);
    assertEquals(CatalogSearch.Availability.PARTIAL, partial.availability);
    assertTrue(partial.books.isEmpty());

    start(Collections.emptySet(), Collections::emptyList, Collections::emptyList);
    CatalogSearch.Result empty = resultWithin(3);
    assertEquals(CatalogSearch.Availability.COMPLETE, empty.availability);
    assertTrue(empty.books.isEmpty());

    start(Collections.emptySet(), this::unavailable, this::unavailable);
    CatalogSearch.Result unavailable = resultWithin(3);
    assertEquals(CatalogSearch.Availability.UNAVAILABLE, unavailable.availability);
    assertTrue(unavailable.books.isEmpty());
  }

  @Test
  public void returnsPartialFailureWhenEmptyCatalogIsTheOnlyOneToRespond() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try {
      start(
          Collections.emptySet(),
          Collections::emptyList,
          () -> {
            release.await();
            return Collections.emptyList();
          });
      CatalogSearch.Result result = resultWithin(11);
      assertEquals(CatalogSearch.Availability.PARTIAL, result.availability);
      assertTrue(result.books.isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void excludesOwnedEditionAndDropsAnObsoleteSearch() throws Exception {
    start(
        Collections.singleton(DUBROVSKY.id),
        () -> Collections.singletonList(DUBROVSKY),
        Collections::emptyList);
    CatalogSearch.Result result = resultWithin(3);
    assertEquals(CatalogSearch.Availability.COMPLETE, result.availability);
    assertTrue(result.books.isEmpty());

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch sourceStarted = new CountDownLatch(1);
    start(
        Collections.emptySet(),
        () -> {
          sourceStarted.countDown();
          release.await();
          return Collections.singletonList(DUBROVSKY);
        },
        Collections::emptyList);
    assertTrue(sourceStarted.await(3, TimeUnit.SECONDS));
    active.set(false);
    release.countDown();
    assertNull(results.poll(2, TimeUnit.SECONDS));
  }

  private void start(
      Set<String> excluded, Callable<List<Book>> first, Callable<List<Book>> second) {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                new CatalogSearch(
                        new Handler(Looper.getMainLooper()), excluded, active::get, results::add)
                    .start(jobs, Arrays.asList(first, second)));
  }

  private CatalogSearch.Result resultWithin(long seconds) throws InterruptedException {
    CatalogSearch.Result result = results.poll(seconds, TimeUnit.SECONDS);
    assertNotNull("Catalog search did not finish", result);
    return result;
  }

  private List<Book> unavailable() throws IOException {
    throw new IOException("Test catalog unavailable");
  }
}
