package ru.knigagolos.app;

import android.os.Handler;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CatalogSearch {
  private static final long DEADLINE_MS = 8000;
  private static final long RESULT_GRACE_MS = 900;

  enum Availability {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
  }

  static final class Result {
    final List<Book> books;
    final Availability availability;

    Result(List<Book> books, Availability availability) {
      this.books = books;
      this.availability = availability;
    }
  }

  private final Handler main;
  private final BooleanSupplier active;
  private final Consumer<Result> deliver;
  private final Set<String> excluded;
  private final Map<String, Book> results = new LinkedHashMap<>();
  private final List<Future<?>> requests = new ArrayList<>();
  private final Runnable finish = this::finish;
  private int left;
  private int successes;
  private boolean finished;

  CatalogSearch(
      Handler main, Set<String> excluded, BooleanSupplier active, Consumer<Result> deliver) {
    this.main = main;
    this.excluded = excluded;
    this.active = active;
    this.deliver = deliver;
  }

  void start(ExecutorService jobs, List<Callable<List<Book>>> sources) {
    left = sources.size();
    main.postDelayed(finish, DEADLINE_MS);
    for (Callable<List<Book>> source : sources)
      requests.add(
          jobs.submit(
              () -> {
                List<Book> books;
                try {
                  books = source.call();
                } catch (Exception e) {
                  main.post(() -> accept(null));
                  return;
                }
                main.post(() -> accept(books));
              }));
  }

  private void accept(List<Book> books) {
    if (finished) return;
    if (!active.getAsBoolean()) {
      finish();
      return;
    }
    boolean wasEmpty = results.isEmpty();
    if (books != null) {
      successes++;
      for (Book book : books) {
        if (!excluded.contains(book.id)) results.putIfAbsent(book.id, book);
      }
    }
    if (--left == 0) {
      finish();
    } else if (wasEmpty && !results.isEmpty()) {
      // Give other sources a short chance to return before presenting available books.
      main.postDelayed(finish, RESULT_GRACE_MS);
    }
  }

  private void finish() {
    if (finished) return;
    finished = true;
    main.removeCallbacks(finish);
    for (Future<?> request : requests) request.cancel(true);
    if (!active.getAsBoolean()) return;
    Availability availability =
        successes == requests.size()
            ? Availability.COMPLETE
            : successes == 0 ? Availability.UNAVAILABLE : Availability.PARTIAL;
    deliver.accept(new Result(new ArrayList<>(results.values()), availability));
  }
}
