# Проверка воспроизведения с живым каталогом

Для проверки нужен отдельный Android-эмулятор с API 29 или выше. Запустите его с чистым профилем, включёнными Wi-Fi и мобильными данными и выключенным авиарежимом. Команды ниже временно отключают сеть только в этом эмуляторе и восстанавливают её после прогона. Тест не требует ключа DeepSeek.

Соберите проект из корневого каталога по инструкции в README. Перед запуском укажите серийный номер тестового эмулятора из вывода `adb devices`.

```bash
set -euo pipefail
serial=emulator-5584
[[ "$serial" =~ ^emulator-[0-9]+$ ]]
device() { adb -s "$serial" "$@"; }

./gradlew assembleDebug assembleDebugAndroidTest
device install app/build/outputs/apk/debug/app-debug.apk
device install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

app=ru.slukholist.player.debug
test_class=ru.knigagolos.app.OfflineReplayTest
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"

run_phase() {
  local output
  output=$(device shell am instrument -w -e liveReplay true -e class "$test_class#$1" "$runner")
  printf '%s\n' "$output"
  grep -Fq 'OK (1 test)' <<< "$output"
}
restore_network() {
  device shell cmd connectivity airplane-mode disable
  device shell svc wifi enable
  device shell svc data enable
}

run_phase searchAndPlayFirstPartOnline
trap restore_network EXIT
device shell am force-stop "$app"
device shell cmd connectivity airplane-mode enable
device shell svc wifi disable
device shell svc data disable
run_phase resumeCachedPartOffline
run_phase uncachedBookFailsOfflineWithoutMovingTheBookmark
restore_network
run_phase sameBookPlaysAfterNetworkRecovery

device logcat -d -s OfflineReplayTest:I
```

Сценарий открывает «Войну и мир», проверяет кеш первой части и возобновляет её с закладки без сети. Затем он проверяет ошибку при открытии незагруженного «Медного всадника», сохранение прежней закладки и повтор после возвращения сети. Для повторного прогона используйте новый чистый профиль.

## Поиск и добавление голосом

На отдельном чистом эмуляторе установите оба APK и задайте `device`, `app` и `runner`, как в примере выше. Подготовьте два монофонических WAV-файла в формате PCM 16 bit, 16 кГц. В `find-queen.wav` запишите «Найди аудиокнигу Александра Пушкина Пиковая дама», в `play-queen.wav` — «Да, включи эту книгу». Путь к файлу с временным ключом DeepSeek укажите в переменной `TEST_KEY_FILE`. Тест отправит две аудиозаписи в настоящий ASR и запрос выбора книги в DeepSeek.

```bash
device shell pm grant "$app" android.permission.RECORD_AUDIO
device shell "run-as $app mkdir -p cache"
for wav in find-queen.wav play-queen.wav; do
  device shell -T "run-as $app sh -c 'cat > cache/$wav'" < "$wav"
done
device shell -T "run-as $app sh -c 'umask 077; cat > cache/live-key'" < "$TEST_KEY_FILE"
device shell am instrument -w -e liveVoice true \
  -e class 'ru.knigagolos.app.CatalogTest#liveRussianSpeechAddsAndPlaysTheQueenOfSpades' "$runner"
device logcat -d -s CatalogLive:I
```

Тест нажимает «Сказать» и подаёт записи в точке захвата микрофона. Он проверяет появление книги в истории, позицию воспроизведения, сохранение диалога, открытие книги после перезапуска экрана и наличие на диске аудио только первой из восьми частей. При обычном завершении тест удаляет временные записи и ключ из приложения.

## Переход между частями

Следующий сценарий требует сети и профиля, в котором ещё не открывали «Войну и мир». После проверки первого фрагмента он закрывает экран, продолжает с сохранённой позиции и перематывает к концу части. После автоматического перехода проверяет позицию второй части и содержимое кеша.

```bash
device shell am instrument -w -e liveMultipart true \
  -e class 'ru.knigagolos.app.MultipartTest#streamsOnePartAtATimeAndAdvancesOnCompletion' "$runner"
```
