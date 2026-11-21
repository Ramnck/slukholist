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
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private final ExecutorService jobs = Executors.newCachedThreadPool();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<JSONObject> conversation = new ArrayList<>();
  private List<Book> offers = Collections.emptyList();
  private int offerIndex;
  private Book offeredBook;
  private volatile int turn;
  private int selectionTask;
  private String selected = "", previous = "", status = "", seenError = "";
  private boolean dialogMode, bound, destroyed, pendingPlay;
  private Book.Shelf shelf;
  private BookPlayer service;
  private Credentials credentials;
  private Piper piper;
  private LinearLayout root;
  private Button mic;
  private ImageButton play, back, forward;
  private LinearLayout playerPage, recentPage, recentItems;
  private boolean showingRecent, trackUpSwipe;
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
                  status = service.error;
                }
                if (showingRecent) refreshRecent();
                render();
              };
          if (service.active()) selected = service.current();
          render();
        }

        public void onServiceDisconnected(ComponentName n) {
          service = null;
          render();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    shelf = new Book.Shelf(this);
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
    }
    if (shelf.get(selected) == null && !shelf.all().isEmpty()) selected = shelf.all().get(0).id;
    piper =
        new Piper(
            this,
            message -> {
              endDialog();
              showStatus(message);
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
    LinearLayout card = new GalleryCard(this);
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
    TextView heading = label(30);
    heading.setText("Недавно слушали");
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setPadding(0, dp(14), 0, dp(10));
    recentPage.addView(heading, -1, -2);
    ScrollView scroll = new ScrollView(this);
    recentItems = new LinearLayout(this);
    recentItems.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(recentItems);
    row(recentPage, scroll, 1);
    LinearLayout nav = new LinearLayout(this);
    recentPage.addView(nav, -1, dp(64));
    horizontal(nav, button("К плееру", 0xffeeeeee, () -> showPage(false)));
    horizontal(nav, button("Ещё", 0xffeeeeee, this::toolsMenu));
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

  private final class GalleryCard extends LinearLayout {
    private float startX, startY;

    GalleryCard(Context context) {
      super(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          startX = e.getX();
          startY = e.getY();
          return true;
        case MotionEvent.ACTION_UP:
          float dx = e.getX() - startX, dy = e.getY() - startY;
          if (Math.abs(dx) >= dp(28) && Math.abs(dx) > Math.abs(dy)) {
            browse(dx < 0 ? 1 : -1);
          } else if (Math.abs(dx) < dp(14) && Math.abs(dy) < dp(14)) {
            performClick();
          }
          return true;
        default:
          return true;
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
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
    if (root != null && !showingRecent) {
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        originX = e.getX();
        originY = e.getY();
        int[] xy = new int[2];
        root.getLocationInWindow(xy);
        float y = (originY - xy[1]) / Math.max(1, root.getHeight());
        trackUpSwipe = y >= 0.2f && y <= 0.76f;
      } else if (trackUpSwipe && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
        float rise = originY - e.getY();
        if (rise >= dp(48) && rise > 1.3f * Math.abs(e.getX() - originX)) {
          MotionEvent cancel = MotionEvent.obtain(e);
          cancel.setAction(MotionEvent.ACTION_CANCEL);
          super.dispatchTouchEvent(cancel);
          cancel.recycle();
          trackUpSwipe = false;
          endDialog();
          cancelSelection();
          showPage(true);
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
    if (recent) refreshRecent();
    render();
  }

  private void refreshRecent() {
    recentItems.removeAllViews();
    List<Book> history = shelf.recent();
    if (history.isEmpty()) {
      TextView empty = label(23);
      empty.setText("Здесь появятся книги, которые вы слушали");
      empty.setPadding(dp(12), dp(28), dp(12), dp(28));
      recentItems.addView(empty);
    }
    for (Book b : history) {
      Button item =
          button(
              b.author.isEmpty() ? b.title : b.title + "\n" + b.author,
              0xfff3f3f3,
              () -> {
                if (active() && !service.current().equals(b.id)) service.pause();
                selected = b.id;
                status = "";
                showPage(false);
                if (!active()) piper.say(b.label(), null);
              });
      item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
      item.setTextSize(25);
      item.setPadding(dp(16), dp(12), dp(16), dp(12));
      item.setMinHeight(dp(76));
      recentItems.addView(item, -1, -2);
    }
  }

  @Override
  public void onBackPressed() {
    if (showingRecent) showPage(false);
    else super.onBackPressed();
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
    mic.setContentDescription(dialogMode ? "Завершить разговор" : "Сказать");
    play.setImageResource(current ? R.drawable.control_pause : R.drawable.control_play);
    play.setContentDescription(current ? "Пауза" : "Воспроизвести");
    play.setEnabled(b != null && !pendingPlay);
    back.setEnabled(offeredBook == null);
    forward.setEnabled(offeredBook == null);
    String normal =
        current && !service.isPlaying() ? "Открываю запись…" : "Свайп вверх — недавние книги";
    hint.setText(status.isEmpty() ? normal : status);
  }

  private void showStatus(String s) {
    status = s;
    render();
  }

  private void browse(int dir) {
    List<Book> books = shelf.all();
    if (books.isEmpty()) return;
    int idx = 0;
    for (int i = 0; i < books.size(); i++) if (books.get(i).id.equals(selected)) idx = i;
    int next = Math.max(0, Math.min(books.size() - 1, idx + dir));
    if (next == idx) return;
    cancelSelection();
    endDialog();
    if (active()) service.pause();
    Book b = books.get(next);
    selected = b.id;
    showStatus("");
    piper.say(b.label(), null);
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
    conversation.clear();
    render();
  }

  private void toolsMenu() {
    showPage(false);
    endDialog();
    cancelSelection();
    showStatus("");
    new AlertDialog.Builder(this)
        .setTitle("Ещё")
        .setItems(
            new String[] {
              "Найти аудиокнигу",
              "Книги на телефоне",
              "Добавить MP3 или M4B",
              "Голосовой помощник",
              "Об этой записи"
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
                case 3:
                  settings();
                  break;
                default:
                  info();
              }
            })
        .show();
  }

  private void info() {
    Book b = shelf.get(selected);
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
      showStatus("Введите автора или название");
      return;
    }
    int task = ++selectionTask, ticket = turn;
    showStatus("Ищу «" + query + "»…");
    List<Book> local = new ArrayList<>();
    for (Book b : shelf.all())
      if (b.label().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) local.add(b);
    List<Book> found = new ArrayList<>(local);
    int[] left = {2}, failed = {0};
    for (int i = 0; i < 2; i++) {
      final int source = i;
      jobs.execute(
          () -> {
            List<Book> result = new ArrayList<>();
            boolean error = false;
            try {
              result = source == 0 ? Catalog.librivox(query) : Catalog.commons(query);
            } catch (Exception e) {
              error = true;
            }
            List<Book> batch = result;
            boolean problem = error;
            main.post(
                () -> {
                  if (destroyed || task != selectionTask || (spoken && ticket != turn)) return;
                  for (Book b : batch)
                    if (found.stream().noneMatch(old -> old.id.equals(b.id))) found.add(b);
                  if (problem) failed[0]++;
                  if (--left[0] != 0) return;
                  String warning = failed[0] > 0 ? "Часть каталогов недоступна. " : "";
                  if (found.isEmpty()) {
                    String s =
                        failed[0] == 2
                            ? "Каталоги недоступны. Попробуйте позже."
                            : warning + "Запись не найдена. Попробуем другое название?";
                    if (spoken) reply(s);
                    else showStatus(s);
                    return;
                  }
                  if (spoken) {
                    offers = found;
                    offerIndex = 0;
                    offer();
                  } else {
                    showStatus(warning);
                    new AlertDialog.Builder(this)
                        .setTitle("Выберите запись")
                        .setItems(
                            found.stream()
                                .map(b -> b.label() + " · " + b.source)
                                .toArray(String[]::new),
                            (d, n) -> resolve(found.get(n), false))
                        .setNegativeButton("Назад", null)
                        .show();
                  }
                });
          });
    }
  }

  private void offer() {
    offeredBook = null;
    resolve(offers.get(offerIndex), true);
  }

  private void resolve(Book candidate, boolean spoken) {
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
                    offeredBook = b;
                    offers.set(offerIndex, b);
                    reply(b.label() + ". Включить?");
                  } else startBook(b);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (destroyed || task != selectionTask || (spoken && ticket != turn)) return;
                  if (spoken) {
                    offers.remove(offerIndex);
                    if (!offers.isEmpty()) {
                      offerIndex %= offers.size();
                      offer();
                    } else reply("Запись недоступна. Поищем другую?");
                  } else showStatus("Запись недоступна. Выберите другую.");
                });
          }
        });
  }

  private void settings() {
    EditText key = new EditText(this);
    key.setSingleLine();
    key.setHint("API-ключ DeepSeek");
    key.setInputType(129);
    new AlertDialog.Builder(this)
        .setTitle("Голосовой помощник")
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
                showStatus("Не удалось сохранить ключ");
              }
            })
        .setNeutralButton(
            "Удалить ключ",
            (d, w) -> {
              try {
                credentials.save("");
                showStatus("Ключ удалён");
              } catch (Exception e) {
                showStatus("Ошибка хранилища");
              }
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void voice() {
    if (showingRecent) showPage(false);
    try {
      if (credentials.read().isEmpty()) {
        settings();
        return;
      }
    } catch (Exception e) {
      showStatus("Обновите ключ DeepSeek");
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 44);
      return;
    }
    cancelSelection();
    piper.stop();
    previous = active() ? service.current() : selected;
    if (active()) service.pause();
    dialogMode = true;
    turn++;
    conversation.clear();
    offers = Collections.emptyList();
    listen();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
    super.onRequestPermissionsResult(code, perms, granted);
    if (code == 44 && granted.length > 0) {
      if (granted[0] == PackageManager.PERMISSION_GRANTED) voice();
      else showStatus("Для разговора нужен микрофон");
    }
  }

  private void listen() {
    int ticket = turn;
    showStatus("Слушаю…");
    Vibrator v = getSystemService(Vibrator.class);
    if (v != null) v.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE));
    jobs.execute(
        () -> {
          try {
            byte[] wav = Microphone.listen(this, () -> ticket == turn);
            if (ticket != turn) return;
            if (wav == null) {
              main.post(
                  () -> {
                    if (ticket == turn) {
                      endDialog();
                      showStatus("Нажмите «Сказать», когда будете готовы");
                    }
                  });
              return;
            }
            main.post(
                () -> {
                  if (ticket == turn) showStatus("Распознаю…");
                });
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
          endDialog();
          String error = e.getMessage() == null ? "" : e.getMessage();
          String msg =
              error.contains("401")
                  ? "Проверьте ключ DeepSeek"
                  : error.contains("402")
                      ? "Проверьте баланс DeepSeek"
                      : "Не удалось получить ответ. Проверьте сеть и попробуйте снова.";
          showStatus(msg);
          piper.say(msg, null);
        });
  }

  private void remember(String role, String text) {
    try {
      conversation.add(Assistant.message(role, text));
      while (conversation.size() > 16) conversation.remove(0);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void interpret(String text) {
    if (text.isEmpty()) {
      reply("Не расслышал. Повторите?");
      return;
    }
    remember("user", text);
    String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^а-яё0-9 ]", "").trim();
    if (normalized.matches("пауза|стоп|отмена|закончить разговор")) {
      endDialog();
      showStatus("");
      return;
    }
    if (!offers.isEmpty() && normalized.matches("да|да включи|включи|давай")) {
      startBook(offers.get(offerIndex));
      return;
    }
    if (normalized.matches("продолжить|продолжай|читай дальше|верни мою книгу")) {
      resumePrevious();
      return;
    }
    int ticket = turn;
    List<JSONObject> messages = new ArrayList<>(conversation);
    Book b = shelf.get(selected);
    String context =
        (b == null ? "" : b.label())
            + (offers.isEmpty() ? "" : "; предложено: " + offers.get(offerIndex).label());
    showStatus("Думаю…");
    jobs.execute(
        () -> {
          try {
            JSONObject result = Assistant.respond(credentials.read(), messages, context);
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

  private void act(JSONObject r) {
    switch (r.optString("action")) {
      case "search":
        String q = r.optString("query").trim();
        if (q.isEmpty()) reply("Какого автора поищем?");
        else search(q, true);
        break;
      case "next":
        if (offerIndex + 1 < offers.size()) {
          offerIndex++;
          offer();
        } else reply("Это все найденные записи. Что ещё поищем?");
        break;
      case "play":
        if (!offers.isEmpty()) startBook(offers.get(offerIndex));
        else resumePrevious();
        break;
      case "resume":
        resumePrevious();
        break;
      case "seek":
        selected = previous;
        move(Math.max(-86400, Math.min(86400, r.optInt("seconds", -10))));
        resumePrevious();
        break;
      case "timer":
        if (service != null) {
          service.setTimer(Math.max(1, Math.min(240, r.optInt("minutes", 30))));
          resumePrevious();
        }
        break;
      case "end":
      case "pause":
        endDialog();
        showStatus("");
        break;
      default:
        reply(r.optString("text", "Какую книгу хотите послушать?"));
    }
  }

  private void reply(String s) {
    int ticket = turn;
    remember("assistant", s);
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
    startActivityForResult(i, 17);
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
                showStatus("Добавлено в библиотеку");
              })
          .show();
    } catch (Exception e) {
      showStatus("Не удалось сохранить доступ к файлу");
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("selected", selected);
    b.putBoolean("recent", showingRecent);
  }

  @Override
  protected void onStop() {
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
