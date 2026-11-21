package ru.knigagolos.app;

import android.app.*;
import android.content.*;
import android.hardware.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class BookPlayer extends Service implements SensorEventListener {
  final class Local extends Binder {
    BookPlayer service() {
      return BookPlayer.this;
    }
  }

  // Activity and service lifecycle callbacks run on the same main thread.
  static int visibleActivities;
  private final Local binder = new Local();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Book.Shelf shelf;
  private ExoPlayer player;
  private StandaloneDatabaseProvider database;
  private SimpleCache cache;
  private DataSource.Factory sources;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private SensorManager sensors;
  private MediaSession session;
  private Book book;
  private int track;
  private boolean ready, faceDown;
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
    if (visibleActivities == 0) return;
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
      Book next = shelf.get(intent.getStringExtra("id"));
      startForeground(NOTIFICATION, notification());
      if (visibleActivities == 0) {
        pause();
        return START_NOT_STICKY;
      }
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
    try {
      if (sources == null) {
        database = new StandaloneDatabaseProvider(this);
        cache =
            new SimpleCache(
                new File(getCacheDir(), "chapters"),
                new LeastRecentlyUsedCacheEvictor(128L * 1024 * 1024),
                database);
        CacheDataSource.Factory remote =
            new CacheDataSource.Factory()
                .setCache(cache)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setUpstreamDataSourceFactory(
                    new DefaultHttpDataSource.Factory()
                        .setUserAgent("Slukholist (Android audiobook player)")
                        .setConnectTimeoutMs(12000)
                        .setReadTimeoutMs(20000));
        // Asset and content URIs go straight to the device. Only network bytes use the cache.
        sources = new DefaultDataSource.Factory(this, remote);
      }
      ExoPlayer p =
          new ExoPlayer.Builder(this)
              .setMediaSourceFactory(new DefaultMediaSourceFactory(sources))
              .build();
      player = p;
      p.setAudioAttributes(
          new androidx.media3.common.AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
              .build(),
          false);
      p.setWakeMode(C.WAKE_MODE_LOCAL);
      p.addListener(
          new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
              if (player != p) return;
              if (state == Player.STATE_READY) {
                handler.removeCallbacks(loadingTimeout);
                if (!ready) {
                  ready = true;
                  begin(p);
                }
              } else if (state == Player.STATE_BUFFERING) {
                handler.removeCallbacks(loadingTimeout);
                handler.postDelayed(loadingTimeout, 45000);
              } else if (state == Player.STATE_ENDED) {
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
              }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
              if (player == p) notifyUi();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
              if (player == p) fail(mediaError());
            }
          });
      p.setMediaItem(MediaItem.fromUri(book.tracks.get(track)), position);
      p.prepare();
      p.play();
      notifyUi();
    } catch (RuntimeException e) {
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

  private void begin(ExoPlayer p) {
    if (player != p) return;
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
    if (ready && book != null && player != null)
      shelf.mark(book.id, track, (int) Math.min(Integer.MAX_VALUE, player.getCurrentPosition()));
  }

  private final Runnable loadingTimeout =
      () -> {
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING)
          fail(
              Failures.offline(this)
                  ? Failures.OFFLINE
                  : "Источник долго не отвечает. Попробуйте другую запись.");
      };

  private void releasePlayer() {
    handler.removeCallbacks(loadingTimeout);
    ready = false;
    if (player != null) {
      ExoPlayer p = player;
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
    long duration = player.getDuration();
    player.seekTo(Math.max(0, duration == C.TIME_UNSET ? ms : Math.min(ms, duration - 1)));
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
    pause();
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
  public void onTaskRemoved(Intent intent) {
    pause();
    super.onTaskRemoved(intent);
  }

  @Override
  public IBinder onBind(Intent i) {
    return binder;
  }

  @Override
  public void onDestroy() {
    changed = null;
    persist();
    releasePlayer();
    handler.removeCallbacksAndMessages(null);
    audio.abandonAudioFocusRequest(focus);
    session.release();
    if (cache != null) cache.release();
    if (database != null) database.close();
    super.onDestroy();
  }
}
