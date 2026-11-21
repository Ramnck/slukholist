package ru.knigagolos.app;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Credentials {
  private final Context context;

  Credentials(Context c) {
    context = c;
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    String alias = "deepseek-book-key";
    if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
    KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
    g.init(
        new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return g.generateKey();
  }

  void save(String value) throws Exception {
    var prefs = context.getSharedPreferences("voice", 0);
    if (value.isEmpty()) {
      prefs.edit().clear().apply();
      return;
    }
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    prefs
        .edit()
        .putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
        .putString(
            "key",
            Base64.encodeToString(
                c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
        .apply();
  }

  String read() throws Exception {
    var prefs = context.getSharedPreferences("voice", 0);
    if (!prefs.contains("key")) return "";
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), 0)));
    return new String(
        c.doFinal(Base64.decode(prefs.getString("key", ""), 0)), StandardCharsets.UTF_8);
  }
}
