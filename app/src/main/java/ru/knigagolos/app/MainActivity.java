package ru.knigagolos.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.viewpager2.widget.ViewPager2;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private final ExecutorService jobs = Executors.newCachedThreadPool();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Conversation conversation;
  private List<Book> offers = Collections.emptyList();
  private int offerIndex;
  private Book offeredBook;
  private volatile int turn;
  private int selectionTask;
  private String selected = "", previous = "", status = "", seenError = "";
  private boolean dialogMode, bound, destroyed, pendingPlay;
  private Book.Shelf shelf;
  private List<Book> culture;
  Microphone.Input voiceInput = Microphone::listen;
  private BookPlayer service;
  private Credentials credentials;
  private Piper piper;
  private LinearLayout root;
  private Button mic;
  private ImageButton play, back, forward;
  private LinearLayout playerPage, recentPage;
  private ViewPager2 libraryPager;
  private TextView libraryPosition;
  private ImageButton libraryPlay;
  private String restoredLibraryId = "";
  private BookCarousel carousel;
  private boolean showingRecent, trackUpSwipe, openingGesture;
  private final Runnable announcePage =
      () -> {
        Book b = libraryBook();
        if (showingRecent && !dialogMode && b != null && !destroyed) piper.say(b.label(), null);
      };

  private float originX, originY;
  private TextView author, title, hint;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          service = ((BookPlayer.Local) b).service();
          service.changed =
              () -> {
                pendingPlay = false;
                if (!service.error.isEmpty() && !service.error.equals(seenError)) {
                  seenError = service.error;
                  reportError(service.error);
                }
                render();
              };
          if (service.active()) selected = service.current();
          if (!service.error.isEmpty()) service.changed.run();
          else render();
        }

        public void onServiceDisconnected(ComponentName n) {
          service = null;
          render();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    culture = Catalog.culture(this);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    shelf = new Book.Shelf(this);
    conversation = new Conversation(getSharedPreferences("conversation", MODE_PRIVATE));
    credentials = new Credentials(this);
    if (!shelf.data.getBoolean("seeded", false)) {
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(getAssets().open("starter.json")))) {
        StringBuilder s = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) s.append(line);
        JSONArray a = new JSONArray(s.toString());
        for (int i = 0; i < a.length(); i++) shelf.put(Book.parse(a.getJSONObject(i)), false);
        shelf.data.edit().putBoolean("seeded", true).apply();
      } catch (Exception e) {
        status = "Добавьте книгу через библиотеку";
      }
    }
    if (state != null) {
      selected = state.getString("selected", "");
      showingRecent = state.getBoolean("recent");
      restoredLibraryId = state.getString("libraryBook", "");
    }
    if (shelf.get(selected) == null && !shelf.all().isEmpty()) selected = shelf.all().get(0).id;
    piper =
        new Piper(
            this,
            message -> {
              String lastStatus = status;
              endDialog();
              showStatus(lastStatus.isEmpty() ? message : lastStatus + " " + message);
            });
    layout();
    showPage(showingRecent);
    bound = bindService(new Intent(this, BookPlayer.class), connection, BIND_AUTO_CREATE);
  }

  private int dp(float v) {
    return Math.round(getResources().getDisplayMetrics().density * v);
  }

  private void layout() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);
    int pad = dp(10);
    root.setPadding(pad, pad, pad, pad);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, in) -> {
            android.graphics.Insets bars =
                in.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return in;
          });
    }
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    getWindow().setStatusBarColor(Color.WHITE);
    getWindow().setNavigationBarColor(Color.WHITE);
    setContentView(root);
    mic =
        button(
            "Сказать",
            0xff6b34df,
            () -> {
              if (dialogMode) {
                endDialog();
                showStatus("");
              } else voice();
            });
    mic.setTextColor(Color.WHITE);
    mic.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    row(root, mic, 0.16f);
    playerPage = new LinearLayout(this);
    playerPage.setOrientation(LinearLayout.VERTICAL);
    row(root, playerPage, 0.84f);
    LinearLayout card = new LinearLayout(this);
    card.setGravity(Gravity.CENTER);
    card.setOrientation(LinearLayout.VERTICAL);
    row(playerPage, card, 0.29f);
    author = label(25);
    title = label(35);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    card.setPadding(0, dp(5), 0, dp(5));
    card.addView(author, -1, -2);
    row(card, title, 1);
    hint = label(17);
    card.addView(hint, -1, -2);
    card.setOnClickListener(
        v -> {
          Book b = shelf.get(selected);
          if (b != null && !dialogMode && !active()) piper.say(b.label(), null);
        });

    LinearLayout seek = new LinearLayout(this);
    row(playerPage, seek, 0.20f);
    back = control(R.drawable.control_back, "Назад на 10 секунд", () -> move(-10));
    forward = control(R.drawable.control_forward, "Вперёд на 10 секунд", () -> move(10));
    horizontal(seek, back);
    horizontal(seek, forward);
    play = control(R.drawable.control_play, "Воспроизвести", this::toggle);
    row(playerPage, play, 0.51f);

    recentPage = new LinearLayout(this);
    recentPage.setOrientation(LinearLayout.VERTICAL);
    row(root, recentPage, 0.84f);
    libraryPosition = label(18);
    recentPage.addView(libraryPosition, -1, -2);
    libraryPager = new ViewPager2(this);
    libraryPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
    row(recentPage, libraryPager, 0.68f);
    libraryPager.registerOnPageChangeCallback(
        new ViewPager2.OnPageChangeCallback() {
          @Override
          public void onPageSelected(int index) {
            updateLibraryPosition();
          }

          @Override
          public void onPageScrollStateChanged(int state) {
            main.removeCallbacks(announcePage);
            if (state == ViewPager2.SCROLL_STATE_IDLE && showingRecent)
              main.postDelayed(announcePage, 130);
            else if (state == ViewPager2.SCROLL_STATE_DRAGGING && !dialogMode) piper.stop();
          }
        });
    libraryPlay =
        control(
            R.drawable.control_play,
            "Играть выбранную книгу",
            () -> {
              Book b = libraryBook();
              if (b != null) startBook(b);
            });
    row(recentPage, libraryPlay, 0.32f);
    LinearLayout nav = new LinearLayout(this);
    recentPage.addView(nav, -1, dp(48));
    Button books = button("Книги", Color.WHITE, this::toolsMenu);
    Button preferences = button("Настройки", Color.WHITE, this::settings);
    books.setTextSize(17);
    preferences.setTextSize(17);
    horizontal(nav, books);
    horizontal(nav, preferences);
    scaleLabels(getResources().getConfiguration());
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    scaleLabels(config);
  }

  private void scaleLabels(Configuration config) {
    boolean wide = config.screenWidthDp >= 600 && config.screenHeightDp >= 450;
    title.setAutoSizeTextTypeUniformWithConfiguration(
        wide ? 36 : 26, wide ? 90 : 60, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    author.setTextSize(wide ? 38 : 29);
    mic.setTextSize(wide ? 40 : 30);

    hint.setTextSize(wide ? 22 : 17);
  }

  private TextView label(int size) {
    TextView t = new TextView(this);
    t.setGravity(Gravity.CENTER);
    t.setTextColor(Color.BLACK);
    t.setTextSize(size);
    t.setMaxLines(size == 35 ? 3 : 2);
    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    return t;
  }

  private Button button(String text, int color, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextColor(Color.BLACK);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
    b.setTextSize(28);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp(5), 0, dp(5), 0);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private ImageButton control(int icon, String description, Runnable action) {
    ImageButton b = new ImageButton(this);
    b.setImageResource(icon);
    b.setContentDescription(description);
    b.setScaleType(ImageView.ScaleType.FIT_CENTER);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22000000),
            null,
            new android.graphics.drawable.ColorDrawable(Color.WHITE)));
    b.setPadding(dp(8), dp(7), dp(8), dp(7));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) openingGesture = false;
    if (openingGesture) return true;
    if (root != null && !showingRecent) {
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        originX = e.getX();
        originY = e.getY();
        int[] xy = new int[2];
        root.getLocationInWindow(xy);
        float y = (originY - xy[1]) / Math.max(1, root.getHeight());
        trackUpSwipe = y >= 0.18f && y <= 1f;
      } else if (trackUpSwipe && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
        float rise = originY - e.getY();
        if (rise >= dp(48) && rise > 1.3f * Math.abs(e.getX() - originX)) {
          MotionEvent cancel = MotionEvent.obtain(e);
          cancel.setAction(MotionEvent.ACTION_CANCEL);
          super.dispatchTouchEvent(cancel);
          cancel.recycle();
          trackUpSwipe = false;
          openingGesture = true;
          boolean talking = dialogMode;
          turn++;
          clearOffers();
          piper.stop();
          if (active()) service.pause();
          status = "";
          showPage(true);
          libraryPager.setCurrentItem(Math.min(1, Math.max(0, carousel.getItemCount() - 1)), false);
          if (talking) reply(currentLabel());
          return true;
        }
      }
    }
    return super.dispatchTouchEvent(e);
  }

  private void showPage(boolean recent) {
    showingRecent = recent;
    playerPage.setVisibility(recent ? View.GONE : View.VISIBLE);
    recentPage.setVisibility(recent ? View.VISIBLE : View.GONE);
    main.removeCallbacks(announcePage);
    if (recent) {
      List<Book> books = shelf.recent();
      // On a fresh install both starter recordings are available to try.
      if (books.isEmpty()) books = shelf.all();
      carousel = new BookCarousel(books, announcePage);
      libraryPager.setAdapter(carousel);
      libraryPager.setCurrentItem(carousel.indexOf(restoredLibraryId), false);
      restoredLibraryId = "";
      updateLibraryPosition();
      main.postDelayed(announcePage, 150);
    } else piper.stop();
    render();
  }

  private Book libraryBook() {
    return carousel == null ? null : carousel.at(libraryPager.getCurrentItem());
  }

  private void updateLibraryPosition() {
    if (carousel == null) return;
    int count = carousel.getItemCount();
    libraryPosition.setText(
        !status.isEmpty()
            ? status
            : count == 0
                ? "Библиотека пуста"
                : "Библиотека  ·  " + (libraryPager.getCurrentItem() + 1) + " из " + count);
    libraryPlay.setEnabled(count > 0);
  }

  @Override
  public void onBackPressed() {
    if (showingRecent) {
      endDialog();
      status = "";
      showPage(false);
    } else super.onBackPressed();
  }

  private void row(LinearLayout group, View v, float weight) {
    group.addView(v, new LinearLayout.LayoutParams(-1, 0, weight));
  }

  private void horizontal(LinearLayout group, View v) {
    group.addView(v, new LinearLayout.LayoutParams(0, -1, 1));
  }

  private boolean active() {
    return service != null && service.active();
  }

  private void render() {
    Book b = offeredBook == null ? shelf.get(selected) : offeredBook;
    author.setText(b == null ? "" : b.author);
    title.setMaxLines(b != null && b.title.length() <= 20 ? 1 : 2);
    title.setText(b == null ? "Выберите книгу" : b.title);
    boolean current = active() && service.current().equals(selected);
    if (showingRecent) updateLibraryPosition();
    mic.setText(dialogMode ? "Завершить" : "Сказать");
    mic.setContentDescription(dialogMode ? "Завершить разговор" : "Сказать");
    play.setImageResource(current ? R.drawable.control_pause : R.drawable.control_play);
    play.setContentDescription(current ? "Пауза" : "Воспроизвести");
    play.setEnabled(b != null && !pendingPlay);
    back.setEnabled(offeredBook == null);
    forward.setEnabled(offeredBook == null);
    String normal =
        current && !service.isPlaying() ? "Открываю запись…" : "Свайп вверх — следующая книга";
    hint.setText(status.isEmpty() ? normal : status);
  }

  private void showStatus(String s) {
    status = s;
    render();
  }

  private void reportError(String message) {
    main.removeCallbacks(announcePage);
    cancelSelection();
    if (active()) service.pause();
    endDialog();
    showStatus(message);
    piper.say(message, null);
  }

  private void toggle() {
    if (offeredBook != null) {
      startBook(offeredBook);
      return;
    }
    if (active() && service.current().equals(selected)) {
      endDialog();
      service.pause();
      showStatus("");
    } else {
      Book b = shelf.get(selected);
      if (b != null) startBook(b);
    }
  }

  private void startBook(Book b) {
    if (showingRecent) showPage(false);
    if (dialogMode) conversation.add("assistant", "Выбрана книга «" + b.label() + "».");
    cancelSelection();
    endDialog();
    selected = b.id;
    if (shelf.get(b.id) == null) shelf.put(b, false);
    seenError = "";
    if (service != null) service.error = "";
    pendingPlay = true;
    showStatus("");
    BookPlayer.start(this, b.id);
  }

  private void move(int seconds) {
    if (active() && service.current().equals(selected)) {
      service.skip(seconds);
      return;
    }
    Book b = shelf.get(selected);
    if (b == null) return;
    int p =
        (int) Math.max(0, Math.min(Integer.MAX_VALUE, shelf.position(selected) + 1000L * seconds));
    shelf.mark(selected, shelf.track(selected), p);
    showStatus("Позиция в главе: " + p / 60000 + " мин " + p / 1000 % 60 + " с");
  }

  private void cancelSelection() {
    selectionTask++;
  }

  private void endDialog() {
    turn++;
    dialogMode = false;
    piper.stop();
    offers = Collections.emptyList();
    offeredBook = null;
    render();
  }

  private void toolsMenu() {
    main.removeCallbacks(announcePage);
    endDialog();
    cancelSelection();
    showStatus("");
    new AlertDialog.Builder(this)
        .setTitle("Книги")
        .setItems(
            new String[] {
              "Найти аудиокнигу", "Книги на телефоне", "Добавить MP3 или M4B", "Об этой записи"
            },
            (d, i) -> {
              switch (i) {
                case 0:
                  searchBox();
                  break;
                case 1:
                  shelfPicker();
                  break;
                case 2:
                  importFiles();
                  break;
                default:
                  info();
              }
            })
        .show();
  }

  private void info() {
    Book b = showingRecent ? libraryBook() : shelf.get(selected);
    if (b != null)
      new AlertDialog.Builder(this)
          .setTitle(b.title)
          .setMessage(b.source + "\n" + b.credit)
          .setPositiveButton("Закрыть", null)
          .show();
  }

  private void shelfPicker() {
    List<Book> books = shelf.all();
    new AlertDialog.Builder(this)
        .setTitle("Мои книги")
        .setItems(
            books.stream().map(Book::label).toArray(String[]::new),
            (d, i) -> {
              Book b = books.get(i);
              if (active()) service.pause();
              selected = b.id;
              showPage(false);
              showStatus("");
              piper.say(b.label(), null);
            })
        .show();
  }

  private void searchBox() {
    EditText e = new EditText(this);
    e.setSingleLine();
    e.setHint("Автор или название");
    new AlertDialog.Builder(this)
        .setTitle("Поиск аудиокниг")
        .setView(e)
        .setNegativeButton("Назад", null)
        .setPositiveButton("Найти", (d, w) -> search(e.getText().toString().trim(), false))
        .show();
  }

  private void search(String query, boolean spoken) {
    offeredBook = null;
    offers = Collections.emptyList();
    if (query.isEmpty()) {
      reportError("Введите автора или название");
      return;
    }
    if (spoken) stage("Ищу", () -> searchCatalogs(query, true));
    else searchCatalogs(query, false);
  }

  private void searchCatalogs(String query, boolean spoken) {
    searchCatalogs(query, spoken, false);
  }

  private void searchCatalogs(String query, boolean spoken, boolean otherEditions) {
    int task = ++selectionTask, ticket = turn;
    showStatus("Ищу «" + query + "»…");
    List<Book> found = shelf.search(query);
    if (!found.isEmpty() && !otherEditions) {
      presentSearch(found, spoken, "");
      return;
    }
    Set<String> localIds = new HashSet<>();
    for (Book b : found) localIds.add(b.id);
    found = Book.matching(culture, query);
    found.removeIf(b -> localIds.contains(b.id));
    if (!found.isEmpty()) {
      presentSearch(found, spoken, "");
      return;
    }
    if (Failures.offline(this)) {
      reportError(Failures.OFFLINE);
      return;
    }
    CatalogSearch search =
        new CatalogSearch(
            main,
            localIds,
            () -> !destroyed && task == selectionTask && (!spoken || ticket == turn),
            result -> {
              if (!result.books.isEmpty()
                  || result.availability == CatalogSearch.Availability.COMPLETE) {
                presentSearch(result.books, spoken, "");
              } else {
                String message =
                    Failures.offline(this)
                        ? Failures.OFFLINE
                        : result.availability == CatalogSearch.Availability.UNAVAILABLE
                            ? "Каталоги пока не отвечают. Попробуйте чуть позже."
                            : "Часть каталогов недоступна. Повторите поиск чуть позже.";
                if (spoken) reply(message);
                else reportError(message);
              }
            });
    search.start(jobs, Arrays.asList(() -> Catalog.librivox(query), () -> Catalog.commons(query)));
  }

  private void presentSearch(List<Book> found, boolean spoken, String warning) {
    if (found.isEmpty()) {
      String message = warning + "Запись не найдена. Попробуем другое название?";
      if (spoken) reply(message);
      else reportError(message);
    } else if (spoken) {
      offers = found;
      offerIndex = 0;
      resolve(found.get(0), true, warning);
    } else {
      showStatus(warning);
      if (!warning.isEmpty()) piper.say(warning, null);
      new AlertDialog.Builder(this)
          .setTitle("Выберите запись")
          .setItems(
              found.stream().map(b -> b.label() + " · " + b.source).toArray(String[]::new),
              (d, n) -> resolve(found.get(n), false, ""))
          .setNegativeButton("Назад", null)
          .show();
    }
  }

  private void offer() {
    offeredBook = null;
    resolve(offers.get(offerIndex), true, "");
  }

  private void resolve(Book candidate, boolean spoken, String warning) {
    int task = ++selectionTask, ticket = turn;
    showStatus("Проверяю запись…");
    jobs.execute(
        () -> {
          try {
            Book b = Catalog.resolve(candidate);
            main.post(
                () -> {
                  if (destroyed || task != selectionTask || (spoken && ticket != turn)) return;
                  if (spoken) {
                    showPage(false);
                    offeredBook = b;
                    offers.set(offerIndex, b);
                    reply(
                        warning
                            + (offerIndex + 1)
                            + " из "
                            + offers.size()
                            + ". "
                            + b.label()
                            + ". Включить?");
                  } else startBook(b);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (destroyed || task != selectionTask || (spoken && ticket != turn)) return;
                  if (Failures.offline(this)) {
                    reportError(Failures.OFFLINE);
                  } else if (spoken) {
                    offers.remove(offerIndex);
                    if (!offers.isEmpty()) {
                      offerIndex %= offers.size();
                      offer();
                    } else reply("Запись недоступна. Поищем другую?");
                  } else
                    reportError(Failures.network(e, false, "Запись недоступна. Выберите другую."));
                });
          }
        });
  }

  private void settings() {
    main.removeCallbacks(announcePage);
    endDialog();
    EditText key = new EditText(this);
    key.setSingleLine();
    key.setHint("API-ключ DeepSeek");
    key.setInputType(129);
    new AlertDialog.Builder(this)
        .setTitle("Слухолист · версия " + BuildConfig.VERSION_NAME)
        .setMessage("Реплики отправляются в DeepSeek. Голос Piper работает на телефоне.")
        .setView(key)
        .setPositiveButton(
            "Сохранить",
            (d, w) -> {
              String s = key.getText().toString().trim();
              if (s.isEmpty()) return;
              try {
                credentials.save(s);
                showStatus("Ключ сохранён");
              } catch (Exception e) {
                reportError("Не удалось сохранить ключ");
              }
            })
        .setNeutralButton(
            "Удалить ключ",
            (d, w) -> {
              try {
                credentials.save("");
                showStatus("Ключ удалён");
              } catch (Exception e) {
                reportError("Ошибка хранилища");
              }
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void voice() {
    main.removeCallbacks(announcePage);
    piper.stop();
    try {
      if (credentials.read().isEmpty()) {
        settings();
        reportError("Введите ключ DeepSeek в настройках.");
        return;
      }
    } catch (Exception e) {
      reportError("Обновите ключ DeepSeek");
      return;
    }
    if (Failures.offline(this)) {
      reportError(Failures.OFFLINE);
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 44);
      return;
    }
    cancelSelection();
    piper.stop();
    List<Book> recent = shelf.recent();
    previous = active() ? service.current() : recent.isEmpty() ? selected : recent.get(0).id;
    if (active()) service.pause();
    dialogMode = true;
    turn++;
    offers = Collections.emptyList();
    listen();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
    super.onRequestPermissionsResult(code, perms, granted);
    if (code == 44 && granted.length > 0) {
      if (granted[0] == PackageManager.PERMISSION_GRANTED) voice();
      else reportError("Для разговора нужен микрофон");
    }
  }

  private void stage(String text, Runnable next) {
    int ticket = turn;
    showStatus(text);
    if (dialogMode)
      piper.say(
          text,
          () -> {
            if (ticket == turn && dialogMode) next.run();
          });
    else next.run();
  }

  private void listen() {
    stage("Слушаю", this::recordUtterance);
  }

  private void recordUtterance() {
    int ticket = turn;
    Vibrator v = getSystemService(Vibrator.class);
    if (v != null) v.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE));
    jobs.execute(
        () -> {
          byte[] wav;
          try {
            wav = voiceInput.listen(this, () -> ticket == turn);
          } catch (Exception e) {
            main.post(
                () -> {
                  if (ticket == turn) reportError(Failures.MICROPHONE);
                });
            return;
          }
          main.post(
              () -> {
                if (ticket != turn) return;
                if (wav == null) reportError("Я вас не услышал. Нажмите «Сказать» и повторите.");
                else stage("Принято", () -> transcribe(ticket, wav));
              });
        });
  }

  private void transcribe(int ticket, byte[] wav) {
    jobs.execute(
        () -> {
          try {
            String text = Assistant.transcribe(credentials.read(), wav);
            main.post(
                () -> {
                  if (ticket == turn) interpret(text);
                });
          } catch (Exception e) {
            voiceError(ticket, e);
          }
        });
  }

  private void voiceError(int ticket, Exception e) {
    main.post(
        () -> {
          if (ticket != turn || destroyed) return;
          reportError(
              Failures.network(
                  e, Failures.offline(this), "Помощник не смог ответить. Попробуйте ещё раз."));
        });
  }

  void interpret(String text) {
    if (text.isEmpty()) {
      reply("Не расслышал. Повторите?");
      return;
    }
    conversation.add("user", text);
    String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^а-яё0-9]+", " ").trim();
    if (normalized.matches("открой библиотеку|моя библиотека|недавно прослушанное")) {
      openRecent();
      return;
    }
    if (normalized.equals("пауза")) {
      spokenPause();
      return;
    }
    if (normalized.matches("стоп|отмена|закончить разговор")) {
      endDialog();
      showStatus("");
      return;
    }
    if (offeredBook != null
        && normalized.matches(
            "да|давай|(?:да )?(?:включи|включай|играй|начинай)(?: эту(?: книгу)?| её| книгу)?")) {
      playSelection();
      return;
    }
    if (normalized.matches("продолжить|продолжай|читай дальше|верни мою книгу")) {
      resumePrevious();
      return;
    }
    stage("Думаю", () -> askAssistant(null));
  }

  private void askAssistant(String research) {
    int ticket = turn;
    List<JSONObject> messages = conversation.history();
    Book b = voiceBook();
    List<String> titles = new ArrayList<>();
    for (Book book : shelf.all()) {
      if (titles.size() >= 60) break;
      titles.add(book.label());
    }
    String context =
        "Экран: "
            + (showingRecent ? "библиотека" : "плеер")
            + ". Выбрано: "
            + (b == null ? "" : b.label())
            + (offers.isEmpty() ? "" : ". Предложено: " + offers.get(offerIndex).label())
            + "\nПожелания: "
            + conversation.note()
            + "\nКниги слушателя (первые 60): "
            + new JSONArray(titles);
    jobs.execute(
        () -> {
          try {
            JSONObject result = Assistant.respond(credentials.read(), messages, context, research);
            main.post(
                () -> {
                  if (ticket == turn) act(result);
                });
          } catch (Exception e) {
            voiceError(ticket, e);
          }
        });
  }

  private void resumePrevious() {
    Book b = shelf.get(previous);
    if (b != null) startBook(b);
    else reply("Какую книгу найдём?");
  }

  void act(JSONObject r) {
    String query = r.optString("query").trim();
    if (r.has("reading_note")) conversation.updateNote(r.optString("reading_note").trim());
    switch (r.optString("action")) {
      case "research_books":
      case "research_info":
        if (query.isEmpty()) {
          reply("О чём поискать?");
          break;
        }
        stage(
            "Ищу",
            () -> {
              List<Book> local = shelf.search(query);
              if (r.optString("action").equals("research_books") && !local.isEmpty())
                presentSearch(local, true, "");
              else askAssistant(query);
            });
        break;
      case "library":
        openRecent();
        break;
      case "player":
        Book visible = voiceBook();
        if (visible != null && shelf.get(visible.id) != null) selected = visible.id;
        clearOffers();
        showPage(false);
        reply(visible == null ? "Плеер открыт." : "Плеер. " + currentLabel());
        break;
      case "search_library":
        stage("Ищу", () -> localBooks(query, false));
        break;
      case "choose":
        navigate(r.optInt("number", 0) - 1);
        break;
      case "info":
        Book info = voiceBook();
        if (info == null) reply("Книга пока не выбрана.");
        else
          reply(
              info.label()
                  + ". "
                  + info.source
                  + ". Глава "
                  + (shelf.track(info.id) + 1)
                  + ". Позиция в главе: "
                  + shelf.position(info.id) / 60000
                  + " мин.");
        break;
      case "settings":
        settings();
        piper.say("Настройки помощника.", null);
        break;
      case "import_files":
        endDialog();
        importFiles();
        break;
      case "other_editions":
        if (query.isEmpty()) reply("Какую книгу поищем?");
        else stage("Ищу", () -> searchCatalogs(query, true, true));
        break;
      case "search":
        if (query.isEmpty()) reply("Какого автора поищем?");
        else search(query, true);
        break;
      case "next":
      case "previous":
        int step = r.optString("action").equals("next") ? 1 : -1;
        if (offers.isEmpty() && !showingRecent) {
          restoredLibraryId = selected;
          showPage(true);
        }
        navigate((offers.isEmpty() ? libraryPager.getCurrentItem() : offerIndex) + step);
        break;
      case "play":
        if (offeredBook != null
            && !Book.matching(Collections.singletonList(offeredBook), query).isEmpty())
          playSelection();
        else if (!query.isEmpty()) stage("Ищу", () -> localBooks(query, true));
        else playSelection();
        break;
      case "resume":
        resumePrevious();
        break;
      case "seek":
        if (voiceBook() == null || !r.has("seconds")) {
          reply("Какую книгу и на сколько секунд перемотать?");
          break;
        }
        Book seekBook = voiceBook();
        if (shelf.get(seekBook.id) == null) shelf.put(seekBook, false);
        selected = seekBook.id;
        move(Math.max(-86400, Math.min(86400, r.optInt("seconds"))));
        startBook(seekBook);
        break;
      case "timer":
        if (service == null) reportError("Плеер ещё открывается. Попробуйте ещё раз.");
        else if (!r.has("minutes") || r.optInt("minutes", -1) < 0)
          reply("На сколько минут поставить таймер?");
        else {
          service.setTimer(Math.min(240, r.optInt("minutes")));
          playSelection();
        }
        break;
      case "pause":
        spokenPause();
        break;
      case "end":
        endDialog();
        showStatus("");
        break;
      case "answer":
      case "chat":
        reply(r.optString("text", "Какую книгу хотите послушать?"));
        break;
      default:
        reply("Не понял команду. Скажите по-другому.");
    }
  }

  private void spokenPause() {
    if (dialogMode) conversation.add("assistant", "Пауза.");
    if (active()) service.pause();
    endDialog();
    showStatus("Пауза");
    piper.say("Пауза", null);
  }

  private Book voiceBook() {
    if (offeredBook != null) return offeredBook;
    return showingRecent ? libraryBook() : shelf.get(selected);
  }

  private String currentLabel() {
    Book b = voiceBook();
    return b == null ? "Книг пока нет." : b.label();
  }

  private void playSelection() {
    Book b = voiceBook();
    if (b == null) reply("Какую книгу включить?");
    else startBook(b);
  }

  private void clearOffers() {
    cancelSelection();
    offers = Collections.emptyList();
    offeredBook = null;
  }

  private void openRecent() {
    clearOffers();
    if (active()) service.pause();
    showPage(true);
    reply("Библиотека. " + currentLabel());
  }

  private void navigate(int index) {
    int count = offers.isEmpty() ? showingRecent ? carousel.getItemCount() : 0 : offers.size();
    if (index < 0 || index >= count) {
      reply(
          count == 0
              ? "Сначала откройте библиотеку или найдите книгу."
              : "Такой карточки нет. Всего книг: " + count + ".");
    } else if (!offers.isEmpty()) {
      offerIndex = index;
      offer();
    } else {
      libraryPager.setCurrentItem(index, false);
      reply((index + 1) + " из " + count + ". " + currentLabel());
    }
  }

  private void localBooks(String query, boolean play) {
    clearOffers();
    List<Book> matches = query.isEmpty() ? shelf.all() : shelf.search(query);
    if (matches.isEmpty()) {
      if (play && !query.isEmpty()) searchCatalogs(query, true);
      else reply("В вашей библиотеке ничего не нашлось. Поищем в каталогах?");
    } else if (play && matches.size() == 1) {
      startBook(matches.get(0));
    } else {
      offers = matches;
      offerIndex = 0;
      offer();
    }
  }

  private void reply(String s) {
    int ticket = turn;
    conversation.add("assistant", s);
    showStatus(s);
    piper.say(
        s,
        () -> {
          if (ticket == turn && dialogMode) listen();
        });
  }

  private void importFiles() {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {"audio/*", "application/mp4", "application/octet-stream"})
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try {
      startActivityForResult(i, 17);
    } catch (ActivityNotFoundException e) {
      reportError("Не удалось открыть выбор файлов.");
    }
  }

  @Override
  protected void onActivityResult(int req, int code, Intent data) {
    super.onActivityResult(req, code, data);
    if (req != 17 || code != RESULT_OK || data == null) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null)
      for (int i = 0; i < data.getClipData().getItemCount(); i++)
        uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    if (uris.isEmpty()) return;
    try {
      for (Uri uri : uris)
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      Map<Uri, String> names = new HashMap<>();
      for (Uri uri : uris) {
        String name = "Аудиокнига";
        try (var cursor =
            getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        names.put(uri, name);
      }
      android.icu.text.RuleBasedCollator order =
          (android.icu.text.RuleBasedCollator)
              android.icu.text.Collator.getInstance(new Locale("ru"));
      order.setNumericCollation(true);
      uris.sort((a, b) -> order.compare(names.get(a), names.get(b)));
      EditText e = new EditText(this);
      e.setSingleLine();
      e.setText(names.get(uris.get(0)).replaceFirst("\\.[^.]+$", ""));
      new AlertDialog.Builder(this)
          .setTitle("Название книги")
          .setMessage(
              uris.size() > 1
                  ? "Выбранные файлы станут главами одной книги."
                  : "Как показать книгу в библиотеке?")
          .setView(e)
          .setNegativeButton("Назад", null)
          .setPositiveButton(
              "Добавить",
              (d, w) -> {
                List<String> tracks = new ArrayList<>();
                for (Uri uri : uris) tracks.add(uri.toString());
                Book book =
                    new Book(
                        "file:" + uris.get(0),
                        e.getText().toString().trim().isEmpty()
                            ? "Аудиокнига"
                            : e.getText().toString().trim(),
                        "",
                        "На телефоне",
                        "",
                        tracks);
                shelf.put(book, false);
                selected = book.id;
                showPage(false);
                showStatus("Добавлено в библиотеку");
              })
          .show();
    } catch (Exception e) {
      reportError("Не удалось сохранить доступ к файлу");
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("selected", selected);
    b.putBoolean("recent", showingRecent);
    if (showingRecent && libraryBook() != null) b.putString("libraryBook", libraryBook().id);
  }

  @Override
  protected void onStart() {
    super.onStart();
    BookPlayer.visibleActivities++;
  }

  @Override
  protected void onStop() {
    BookPlayer.visibleActivities--;
    if (BookPlayer.visibleActivities == 0 && !isChangingConfigurations()) {
      if (service != null) service.pause();
      stopService(new Intent(this, BookPlayer.class));
    }
    pendingPlay = false;
    cancelSelection();
    main.removeCallbacks(announcePage);
    piper.stop();
    if (dialogMode) {
      endDialog();
      showStatus("");
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    turn++;
    cancelSelection();
    if (service != null) service.changed = null;
    if (bound) unbindService(connection);
    piper.close();
    jobs.shutdownNow();
    super.onDestroy();
  }
}
