# Sky Launcher — свой главный экран для Skyworth (Google TV)

## Сборка
1. Откройте папку в Android Studio (Ladybug или новее), дождитесь синхронизации Gradle.
2. Build → Build APK(s). Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`.

## Подготовка телевизора
1. Настройки → Система → О системе → 7 раз нажать «Сборка ОС Android TV» → режим разработчика.
2. Настройки → Система → Для разработчиков → включить «Отладка по USB» / «Отладка по сети».
3. Узнать IP телевизора (Настройки → Сеть) и подключиться с компьютера:
   adb connect 192.168.X.X:5555
   (на экране ТВ подтвердить запрос)

## Установка
   adb install -r app-debug.apk

## Вариант А — заменить стандартный лаунчер
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
Нажмите Home — откроется Sky Launcher. После перезагрузки ТВ тоже стартует он.

Посмотреть, какие лаунчеры есть на вашей модели Skyworth (у некоторых свой):
   adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
Лишние отключите той же командой `pm disable-user`.

Вернуть всё как было:
   adb shell pm enable com.google.android.apps.tv.launcherx
   adb shell pm enable com.google.android.tungsten.setupwraith

## Вариант В — без ADB (через Спец. возможности)
Настройки ТВ → Система → Спец. возможности → Sky Launcher → Включить.
Теперь при включении ТВ и по кнопке Home открывается Sky Launcher.

## Вариант Б — режим киоска (нельзя выйти)
Только после сброса до заводских настроек и БЕЗ входа в Google-аккаунт:
   adb install -r app-debug.apk
   adb shell dpm set-device-owner com.sky.launcher/.AdminReceiver
Лаунчер закрепится и станет постоянным домашним экраном.
Снять: из приложения через dpm.clearDeviceOwnerApp() или сброс ТВ.

## Что настроить
- Обои: положите `wallpaper.jpg` в `app/src/main/res/drawable/` и удалите `wallpaper.xml`.
- Колонки сетки: `numColumns` в `res/layout/activity_main.xml`.
- Название: `res/values/strings.xml`.
