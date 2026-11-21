package ru.knigagolos.app;

import android.content.Context;
import android.net.ConnectivityManager;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class Failures {
  static final String OFFLINE = "Нет подключения к интернету.";
  static final String MICROPHONE = "Микрофон недоступен. Проверьте разрешение на микрофон.";

  static final class Http extends IOException {
    final int status;
    final boolean deepSeek;

    Http(int status, String host) {
      super("HTTP " + status);
      this.status = status;
      deepSeek = "api.deepseek.com".equals(host);
    }
  }

  static boolean offline(Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    android.net.NetworkCapabilities capabilities =
        manager.getNetworkCapabilities(manager.getActiveNetwork());
    return capabilities == null
        || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        || capabilities.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
  }

  static String network(Exception error, boolean offline, String fallback) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Http) {
        Http http = (Http) cause;
        if (http.deepSeek && http.status == 402) return "На счёте DeepSeek недостаточно денег.";
        if (http.deepSeek && http.status == 401)
          return "Неверный ключ DeepSeek. Проверьте его в настройках.";
        if (http.deepSeek && http.status == 403) return "У этого ключа нет доступа к помощнику.";
        if (http.status == 429 || http.status == 503) return "Сервис занят. Попробуйте чуть позже.";
        if (http.status == 408 || http.status == 504)
          return "Сервис долго не отвечает. Попробуйте ещё раз.";
        if (http.status >= 500) return "Сервис временно недоступен. Попробуйте позже.";
        return fallback;
      }
    }
    if (offline) return OFFLINE;
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof NoRouteToHostException) return OFFLINE;
      if (cause instanceof UnknownHostException) return "Не удалось подключиться к сервису.";
      if (cause instanceof SocketTimeoutException)
        return "Сервис долго не отвечает. Попробуйте ещё раз.";
    }
    return fallback;
  }
}
