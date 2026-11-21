package ru.knigagolos.app;

import android.app.*;
import android.content.*;
import android.hardware.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.*;

public final class BookPlayer extends Service implements SensorEventListener {
  final class Local extends Binder {
    BookPlayer service() {
      return BookPlayer.this;
    }
  }

  private final Local binder = new Local();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Book.Shelf shelf;
  private MediaPlayer player;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private SensorManager sensors;
  private MediaSession session;
  private Book book;
  private Piper errorVoice;
  private int track;
  private boolean ready, faceDown, seeking;
  private long sleepUntil;
  Runnable changed;
  String error = "";
  private static final String CHANNEL = "listening";
  private static final int NOTIFICATION = 1;
  private static final AudioAttributes SPEECH =
      new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build();

  @Override
  public void onCreate() {
    super.onCreate();
    shelf = new Book.Shelf(this);
    audio = getSystemService(AudioManager.class);
    sensors = getSystemService(SensorManager.class);
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener(
                c -> {
                  if (c < 0) pause();
                })
            .build();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "Чтение", NotificationManager.IMPORTANCE_LOW));
    session = new MediaSession(this, "Слухолист");
    session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPause() {
            pause();
          }

          @Override
          public void onPlay() {
            if (book != null) start(BookPlayer.this, book.id);
          }

          @Override
          public void onRewind() {
            skip(-10);
          }

          @Override
          public void onFastForward() {
            skip(10);
          }

          @Override
          public void onSeekTo(long ms) {
            if (ready) seek((int) Math.min(Integer.MAX_VALUE, ms));
          }
        });
    handler.post(checkpoint);
  }

  static void start(Context c, String id) {
    c.startForegroundService(new Intent(c, BookPlayer.class).setAction("play").putExtra("id", id));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("pause".equals(intent.getAction())) pause();
    if ("play".equals(intent.getAction())) {
      if (errorVoice != null) errorVoice.stop();
      Book next = shelf.get(intent.getStringExtra("id"));
      startForeground(NOTIFICATION, notification());
      if (next == null || next.tracks.isEmpty()) {
        fail("Книга недоступна");
        return START_NOT_STICKY;
      }
      if (isPlaying() && next.id.equals(current())) return START_NOT_STICKY;
      persist();
      releasePlayer();
      book = next;
      error = "";
      track = Math.min(shelf.track(next.id), next.tracks.size() - 1);
      if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        load(shelf.position(next.id));
      else fail("Звук сейчас занят");
    }
    return START_NOT_STICKY;
  }

  private void load(int position) {
    releasePlayer();
    MediaPlayer p = new MediaPlayer();
    player = p;
    try {
      p.setAudioAttributes(SPEECH);
      p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
      p.setOnErrorListener(
          (m, a, b) -> {
            if (player == m) {
              ready = false;
              fail(mediaError());
            }
            return true;
          });
      p.setOnCompletionListener(
          m -> {
            if (player != m) return;
            if (track + 1 < book.tracks.size()) {
              track++;
              shelf.mark(book.id, track, 0);
              load(0);
            } else {
              pause();
              shelf.mark(book.id, 0, 0);
              track = 0;
              notifyUi();
            }
          });
      p.setOnPreparedListener(
          m -> {
            if (player != m) return;
            handler.removeCallbacks(loadingTimeout);
            ready = true;
            if (position > 0) {
              seeking = true;
              m.setOnSeekCompleteListener(
                  mp -> {
                    if (player == mp && seeking) {
                      seeking = false;
                      mp.setOnSeekCompleteListener(null);
                      begin(mp);
                    }
                  });
              m.seekTo(
                  Math.min(position, Math.max(0, m.getDuration() - 1)), MediaPlayer.SEEK_CLOSEST);
            } else begin(m);
          });
      String location = book.tracks.get(track);
      if (location.startsWith("https://")) {
        if (Failures.offline(this)) {
          fail(Failures.OFFLINE);
          return;
        }
        p.setDataSource(
            this,
            Uri.parse(location),
            java.util.Collections.singletonMap(
                "User-Agent", "Slukholist/0.3 (Android audiobook player)"));
        handler.postDelayed(loadingTimeout, 45000);
      } else if (location.startsWith("asset:///")) {
        try (android.content.res.AssetFileDescriptor file =
            getAssets().openFd(location.substring("asset:///".length()))) {
          p.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
        }
      } else p.setDataSource(this, Uri.parse(location));
      p.prepareAsync();
      notifyUi();
    } catch (Exception e) {
      fail(mediaError());
    }
  }

  private String mediaError() {
    if (book.tracks.get(track).startsWith("https://")) {
      return Failures.offline(this)
          ? Failures.OFFLINE
          : "Не удалось загрузить запись. Попробуйте позже.";
    }
    return "Не удалось открыть аудиофайл. Выберите его заново.";
  }

  private void begin(MediaPlayer p) {
    if (player != p) return;
    p.start();
    shelf.put(book, true);
    session.setActive(true);
    Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    session.setMetadata(
        new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, book.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, book.author)
            .build());
    startForeground(NOTIFICATION, notification());
    notifyUi();
  }

  String current() {
    return book == null ? "" : book.id;
  }

  boolean active() {
    return player != null;
  }

  boolean isPlaying() {
    return ready && player != null && player.isPlaying();
  }

  private void persist() {
    if (ready && !seeking && book != null && player != null)
      shelf.mark(book.id, track, player.getCurrentPosition());
  }

  private final Runnable loadingTimeout =
      () -> {
        if (player != null && !ready)
          fail(
              Failures.offline(this)
                  ? Failures.OFFLINE
                  : "Источник долго не отвечает. Попробуйте другую запись.");
      };

  private void releasePlayer() {
    handler.removeCallbacks(loadingTimeout);
    ready = false;
    seeking = false;
    if (player != null) {
      MediaPlayer p = player;
      player = null;
      p.release();
    }
    sensors.unregisterListener(this);
    faceDown = false;
    handler.removeCallbacks(flipPause);
  }

  void pause() {
    persist();
    releasePlayer();
    audio.abandonAudioFocusRequest(focus);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    notifyUi();
  }

  void skip(int seconds) {
    if (!ready || player == null) return;
    seek((int) Math.min(Integer.MAX_VALUE, (long) player.getCurrentPosition() + seconds * 1000L));
  }

  private void seek(int ms) {
    player.seekTo(
        Math.max(0, Math.min(ms, Math.max(0, player.getDuration() - 1))), MediaPlayer.SEEK_CLOSEST);
  }

  void setTimer(int minutes) {
    sleepUntil = minutes == 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60000L;
  }

  private final Runnable checkpoint =
      new Runnable() {
        public void run() {
          persist();
          if (sleepUntil != 0 && SystemClock.elapsedRealtime() >= sleepUntil) {
            sleepUntil = 0;
            pause();
          }
          handler.postDelayed(this, 2000);
        }
      };
  private final Runnable flipPause =
      () -> {
        if (faceDown && isPlaying()) pause();
      };

  @Override
  public void onSensorChanged(SensorEvent e) {
    boolean down = e.values[2] < -8.4 && Math.abs(e.values[0]) < 4 && Math.abs(e.values[1]) < 4;
    if (down == faceDown) return;
    faceDown = down;
    handler.removeCallbacks(flipPause);
    if (down) handler.postDelayed(flipPause, 850);
  }

  @Override
  public void onAccuracyChanged(Sensor s, int accuracy) {}

  private void fail(String message) {
    error = message;
    if (changed != null) {
      pause();
    } else {
      // A book can fail with the screen off after the activity has been destroyed.
      releasePlayer();
      audio.abandonAudioFocusRequest(focus);
      notifyUi();
      if (errorVoice == null) errorVoice = new Piper(this, ignored -> pause());
      errorVoice.say(message, this::pause);
    }
  }

  private void notifyUi() {
    session.setPlaybackState(
        new PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_REWIND
                    | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_SEEK_TO)
            .setState(
                isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                ready ? player.getCurrentPosition() : book == null ? 0 : shelf.position(book.id),
                isPlaying() ? 1f : 0f)
            .build());
    if (changed != null) changed.run();
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, BookPlayer.class).setAction("pause"),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(book == null ? "Слухолист" : book.title)
        .setContentIntent(open)
        .addAction(new Notification.Action.Builder(null, "Пауза", stop).build())
        .setStyle(
            new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0))
        .build();
  }

  @Override
  public IBinder onBind(Intent i) {
    return binder;
  }

  @Override
  public void onDestroy() {
    changed = null;
    if (errorVoice != null) errorVoice.close();
    persist();
    releasePlayer();
    handler.removeCallbacksAndMessages(null);
    audio.abandonAudioFocusRequest(focus);
    session.release();
    super.onDestroy();
  }
}
