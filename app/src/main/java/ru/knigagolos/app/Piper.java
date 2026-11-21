package ru.knigagolos.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

// Native synthesis is serialized. A newer swipe can supersede an unfinished utterance.
final class Piper {
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private OfflineTts tts;
  private MediaPlayer player;
  private volatile int generation;
  private final Consumer<String> error;

  Piper(Context c, Consumer<String> error) {
    context = c.getApplicationContext();
    this.error = error;
  }

  void stop() {
    generation++;
    if (player != null) {
      player.release();
      player = null;
    }
  }

  void say(String text, Runnable done) {
    stop();
    int ticket = generation;
    worker.execute(
        () -> {
          if (ticket != generation) return;
          File clip = null;
          try {
            if (tts == null) init();
            if (ticket != generation) return;
            GeneratedAudio a = tts.generate(text, 0, 0.96f);
            if (ticket != generation) return;
            clip = File.createTempFile("piper-", ".wav", context.getCacheDir());
            if (!a.save(clip.toString())) throw new IOException();
            File file = clip;
            main.post(() -> play(file, ticket, done));
          } catch (Exception | LinkageError e) {
            if (clip != null) clip.delete();
            main.post(
                () -> {
                  if (ticket == generation) error.accept("Не удалось озвучить ответ Piper");
                });
          }
        });
  }

  private void play(File f, int ticket, Runnable done) {
    try {
      if (ticket != generation) return;
      MediaPlayer m = new MediaPlayer();
      player = m;
      m.setDataSource(f.getPath());
      m.prepare();
      m.setOnCompletionListener(
          p -> {
            if (p == player) {
              stop();
              if (done != null) done.run();
            }
          });
      m.start();
    } catch (Exception e) {
      stop();
      error.accept("Не удалось воспроизвести голос");
    } finally {
      f.delete();
    }
  }

  private void init() throws IOException {
    File data = new File(context.getFilesDir(), "piper-data-v1");
    File ready = new File(data, ".ready");
    if (!ready.exists()) {
      copy("piper/espeak-ng-data", data);
      if (!ready.createNewFile() && !ready.exists()) throw new IOException();
    }
    OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
    v.setModel("piper/ru_RU-dmitri-medium.onnx");
    v.setTokens("piper/tokens.txt");
    v.setDataDir(data.getAbsolutePath());
    OfflineTtsModelConfig model = new OfflineTtsModelConfig();
    model.setVits(v);
    model.setNumThreads(2);
    OfflineTtsConfig config = new OfflineTtsConfig();
    config.setModel(model);
    tts = new OfflineTts(context.getAssets(), config);
  }

  private void copy(String path, File out) throws IOException {
    String[] children = context.getAssets().list(path);
    if (children != null && children.length > 0) {
      if (!out.isDirectory() && !out.mkdirs()) throw new IOException();
      for (String c : children) copy(path + "/" + c, new File(out, c));
    } else {
      try (InputStream in = context.getAssets().open(path);
          OutputStream dest = new FileOutputStream(out)) {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) dest.write(buf, 0, n);
      }
    }
  }

  void close() {
    stop();
    worker.execute(
        () -> {
          if (tts != null) tts.release();
        });
    worker.shutdown();
  }
}
