package ru.knigagolos.app;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.function.BooleanSupplier;

final class Microphone {
  interface Input {
    byte[] listen(android.content.Context context, BooleanSupplier active) throws Exception;
  }

  static byte[] listen(android.content.Context context, BooleanSupplier active) throws Exception {
    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new IOException("Микрофон недоступен");
    int rate = 16000;
    int size =
        Math.max(
            4096,
            AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    AudioRecord recorder =
        new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            size);
    ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    try {
      recorder.startRecording();
      long start = SystemClock.elapsedRealtime(), lastSound = 0;
      short[] block = new short[640];
      while (active.getAsBoolean()) {
        int n = recorder.read(block, 0, block.length);
        if (n <= 0) throw new IOException("Микрофон недоступен");
        double energy = 0;
        for (int i = 0; i < n; i++) {
          energy += (double) block[i] * block[i];
          pcm.write(block[i] & 255);
          pcm.write((block[i] >> 8) & 255);
        }
        long now = SystemClock.elapsedRealtime();
        if (Math.sqrt(energy / n) > 190) lastSound = now;
        if (lastSound == 0 && now - start > 7000) return null;
        if ((lastSound != 0 && now - lastSound > 1450) || now - start > 45000) break;
      }
    } finally {
      recorder.release();
    }
    if (!active.getAsBoolean()) return null;
    byte[] samples = pcm.toByteArray();
    ByteBuffer b = ByteBuffer.allocate(samples.length + 44).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952)
        .putInt(samples.length + 36)
        .putInt(0x45564157)
        .putInt(0x20746d66)
        .putInt(16);
    b.putShort((short) 1)
        .putShort((short) 1)
        .putInt(rate)
        .putInt(rate * 2)
        .putShort((short) 2)
        .putShort((short) 16);
    b.putInt(0x61746164).putInt(samples.length).put(samples);
    return b.array();
  }
}
