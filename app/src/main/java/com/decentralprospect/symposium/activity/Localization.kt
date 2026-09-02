package com.decentralprospect.symposium

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import java.util.Locale
import androidx.compose.material3.Text as MaterialText

private const val LANGUAGE_PREFS_NAME = "language_preferences"
private const val PREF_APP_LANGUAGE = "app_language"

enum class AppLanguage(val languageTag: String?, val label: String, val description: String) {
    SYSTEM(null, "Системный", "Использовать язык устройства"),
    RUSSIAN("ru", "Русский", "Всегда использовать русский язык"),
    ENGLISH("en", "English", "Always use English")
}

internal fun MainActivity.loadLanguagePrefs() {
    val prefs = getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(PREF_APP_LANGUAGE, AppLanguage.SYSTEM.name)
    val language = AppLanguage.values().firstOrNull { it.name == raw } ?: AppLanguage.SYSTEM
    appLanguageState = language
    applyAppLanguage(language)
}

internal fun MainActivity.setAppLanguage(language: AppLanguage) {
    appLanguageState = language
    getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_APP_LANGUAGE, language.name)
        .apply()
    applyAppLanguage(language)
}

private fun applyAppLanguage(language: AppLanguage) {
    val locales = language.languageTag
        ?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()
    if (AppCompatDelegate.getApplicationLocales() != locales) {
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

private fun useRussian(): Boolean {
    val appLocale = AppCompatDelegate.getApplicationLocales().get(0)
    return (appLocale ?: Locale.getDefault()).language.equals("ru", ignoreCase = true)
}

private val russianToEnglish = mapOf(
    "Недоступно" to "Unavailable",
    "SymposiumRelay не установлен" to "SymposiumRelay is not installed",
    "QR-код" to "QR code",
    "QR гостя" to "Guest QR code",
    "QR модератора" to "Moderator QR code",
    "Копировать" to "Copy",
    "Скачать" to "Download",
    "IP адрес" to "IP address",
    "Пароль" to "Password",
    "Логин" to "Login",
    "Пользователь" to "User",
    "Гость" to "Guest",
    "Модератор" to "Moderator",
    "Загрузка" to "Loading",
    "Подключиться" to "Connect",
    "Закрыть" to "Close",
    "Закрыть окно" to "Close dialog",
    "Добавить" to "Add",
    "Добавить сервер" to "Add server",
    "Добавить комнату" to "Add room",
    "Обновить" to "Refresh",
    "Обновить список комнат" to "Refresh room list",
    "Обновить список серверов" to "Refresh server list",
    "Обновление списка серверов" to "Refreshing server list",
    "Обновить ссылку модератора" to "Refresh moderator link",
    "Обновить ссылку модератора?" to "Refresh moderator link?",
    "Обновить ссылку" to "Refresh link",
    "Сменить ссылку модератора" to "Change moderator link",
    "Установить" to "Install",
    "Установить SymposiumRelay" to "Install SymposiumRelay",
    "Удалить SymposiumRelay" to "Remove SymposiumRelay",
    "Удалить сервер" to "Delete server",
    "Удалить" to "Delete",
    "Создать" to "Create",
    "Создать встречу" to "Create meeting",
    "Закрыть встречу" to "Close meeting",
    "Закрыть комнату" to "Close room",
    "Главная" to "Home",
    "Меню" to "Menu",
    "Настройки" to "Settings",
    "О приложении" to "About",
    "Поддержка" to "Support",
    "Поддержка в Telegram" to "Telegram support",
    "Электронная почта" to "Email",
    "Не удалось открыть приложение" to "Could not open the app",
    "Доступно обновление" to "Update available",
    "Доступна новая версия Symposium." to "A new version of Symposium is available.",
    "Обновить приложение можно на сайте Symposium. Новости о выпусках доступны в Telegram-канале." to "You can update the app on the Symposium website. Release news is available in the Telegram channel.",
    "Открыть сайт" to "Open website",
    "Управление серверами" to "Manage servers",
    "МОД" to "MOD",
    "Управляйте серверами, комнатами и настройками" to "Manage servers, rooms, and settings",
    "Установка Relay и управление комнатами" to "Relay installation and room management",
    "Тема, язык и приватность" to "Theme, language, and privacy",
    "Версия и информация о приложении" to "Version and app information",
    "Назад" to "Back",
    "Назад к серверам" to "Back to servers",
    "Продолжить" to "Continue",
    "Понятно" to "Got it",
    "Выбрать" to "Select",
    "Исправить" to "Fix",
    "Сохранить" to "Save",
    "Редактировать" to "Edit",
    "Действие" to "Action",
    "Ссылка" to "Link",
    "Ссылка на встречу" to "Meeting link",
    "Ссылка для подключения" to "Connection link",
    "Имя" to "Name",
    "Имя (необязательно)" to "Name (optional)",
    "Название комнаты" to "Room name",
    "Новая встреча" to "New meeting",
    "Подключение к конференции" to "Join conference",
    "Не телефонный разговор" to "Not a phone call",
    "Вы можете оставить поле \"Имя\" пустым" to "You can leave the Name field empty",
    "Подключение к серверу…" to "Connecting to server…",
    "Подключаемся к комнате" to "Connecting to room",
    "Не удалось подключиться" to "Could not connect",
    "Устанавливаем защищённое соединение…" to "Establishing a secure connection…",
    "Повторяем попытку подключения…" to "Retrying the connection…",
    "Готовим подключение…" to "Preparing the connection…",
    "Ссылка содержит некорректный ключ шифрования." to "The link contains an invalid encryption key.",
    "Не удалось проверить защищённое соединение с сервером." to "The secure server connection could not be verified.",
    "Не удалось включить сквозное шифрование." to "End-to-end encryption could not be enabled.",
    "Сервер отклонил подключение." to "The server rejected the connection.",
    "Сквозное шифрование включено" to "End-to-end encryption is on",
    "Вернуться" to "Go back",
    "Отменить подключение" to "Cancel connection",
    "Обновление списка комнат" to "Refreshing room list",
    "Открытые комнаты" to "Open rooms",
    "Открытые комнаты появятся здесь." to "Open rooms will appear here.",
    "Открытых комнат нет." to "There are no open rooms.",
    "Свернуть комнаты" to "Collapse rooms",
    "Развернуть комнаты" to "Expand rooms",
    "Свернуть комнату" to "Collapse room",
    "Развернуть комнату" to "Expand room",
    "Ссылка модератора недоступна" to "Moderator link unavailable",
    "Ссылки готовы" to "Links are ready",
    "Открыть комнату" to "Open room",
    "Ссылка для подключения без прав модератора" to "Connection link without moderator permissions",
    "Ссылка с правами управления комнатой" to "Link with room management permissions",
    "Вставьте ссылку на встречу" to "Paste the meeting link",
    "Ссылка не распознана. Вставьте ссылку Symposium" to "The link was not recognized. Paste a Symposium link",
    "Нажмите, чтобы открыть" to "Tap to open",
    "Пока пусто" to "Nothing here yet",
    "Добавьте сервер, чтобы начать установку" to "Add a server to start installation",
    "Новый сервер" to "New server",
    "Параметры сервера" to "Server settings",
    "Комнаты" to "Rooms",
    "Проверка версии…" to "Checking version…",
    "Операция завершена успешно" to "Operation completed successfully",
    "Логи последней операции" to "Latest operation logs",
    "Установка SymposiumRelay" to "Installing SymposiumRelay",
    "Удаление SymposiumRelay" to "Removing SymposiumRelay",
    "Обновление SymposiumRelay" to "Updating SymposiumRelay",
    "Где взять сервер?" to "Where can I get a server?",
    "Что покупать?" to "What should I buy?",
    "Где лучше покупать?" to "Where should I buy it?",
    "Где не стоит покупать?" to "Which providers should I avoid?",
    "Как настроить при покупке" to "How to configure it when buying",
    "Что делать дальше?" to "What do I do next?",
    "Для конференции нужен обычный VPS: маленький виртуальный сервер с публичным IPv4 и SSH-доступом. Приложение само установит на него SymposiumRelay" to "A conference requires a regular VPS: a small virtual server with a public IPv4 address and SSH access. The app will install SymposiumRelay on it automatically",
    "VPS или облачный сервер на Debian/Ubuntu. Минимум: 1 vCPU и 2 GB RAM. Лучше: 2vCPU и 2 GB RAM. Обязательно нужен публичный IPv4" to "A VPS or cloud server running Debian or Ubuntu. Minimum: 1 vCPU and 2 GB RAM. Recommended: 2 vCPU and 2 GB RAM. A public IPv4 address is required",
    "Покупать стоит у крупных VPS провайдеров не попадающих под ограничения в регионе участников конференции" to "Choose a major VPS provider that is not restricted in the regions where conference participants are located",
    "Лучше не брать сервера у провайдеров, которые попадают под ограничения в регионе участников. Так например сервера от Hetzner и DigitalOcean в России подвергаются ограничениям и работать не будут!" to "Avoid providers that are restricted in participants' regions. For example, Hetzner and DigitalOcean servers are restricted in Russia and will not work there!",
    "Выберите операционную систему Debian, или Ubuntu. Включите IPv4. Сохраните IP-адрес, логин и пароль. Если провайдер просит настроить firewall, разрешите 443/tcp и 32768–60999/udp" to "Choose Debian or Ubuntu. Enable IPv4 and save the IP address, login, and password. If the provider asks you to configure a firewall, allow 443/tcp and 32768–60999/udp",
    "После покупки нажмите «Добавить», введите IP, логин и пароль. При первом подключении подтвердите SSH-ключ, затем установите SymposiumRelay. После установки сервер появится в списке для создания встреч." to "After purchase, tap Add and enter the IP address, login, and password. Confirm the SSH key on the first connection, then install SymposiumRelay. The server will then appear in the meeting creation list.",
    "Выберите сервер, на котором будет открыта новая комната." to "Select the server where the new room will be opened.",
    "Серверов пока нет. Для создания встречи добавьте сервер" to "There are no servers yet. Add one to create a meeting",
    "На этом сервере ещё нельзя создать встречу. Сперва установите SymposiumRelay" to "A meeting cannot be created on this server yet. Install SymposiumRelay first",
    "Для установки нужны сохранённые SSH-логин и пароль. Добавьте сервер заново" to "Saved SSH credentials are required for installation. Add the server again",
    "SymposiumRelay не установлен. Нажмите, чтобы установить." to "SymposiumRelay is not installed. Tap to install it.",
    "Нет TLS pin. Нажмите, чтобы переустановить Relay." to "The TLS pin is missing. Tap to reinstall Relay.",
    "Нет adminToken. Нажмите, чтобы переустановить Relay." to "The admin token is missing. Tap to reinstall Relay.",
    "Ожидание допуска" to "Waiting for approval",
    "Вы подключились как гость. Модератор должен впустить вас в комнату." to "You joined as a guest. A moderator must admit you to the room.",
    "Ожидаем решения модератора…" to "Waiting for the moderator…",
    "Отменить подключение" to "Cancel connection",
    "Закрыть комнату?" to "Close the room?",
    "Закрыть комнату" to "Close room",
    "Введите название комнаты" to "Enter a room name",
    "Введите имя комнаты" to "Enter a room name",
    "Введите название" to "Enter a name",
    "Комната с таким названием уже открыта" to "A room with this name is already open",
    "На этом сервере нельзя создать встречу: SymposiumRelay не установлен" to "A meeting cannot be created on this server because SymposiumRelay is not installed",
    "Не удалось создать встречу" to "Could not create the meeting",
    "Не удалось закрыть комнату" to "Could not close the room",
    "Не удалось обновить ссылку модератора" to "Could not refresh the moderator link",
    "Ссылка модератора обновлена" to "Moderator link refreshed",
    "Ссылка не распознана. Вставьте ссылку в формате Symposium" to "The link was not recognized. Paste a Symposium link",
    "Запомнить SSH-ключ" to "Trust SSH key",
    "Запомнить" to "Trust",
    "SSH-ключ" to "SSH key",
    "Если сервер был переустановлен или вы не уверены в сети, проверьте fingerprint через панель VPS/консоль сервера перед продолжением." to "If the server was reinstalled or you do not trust the network, verify the fingerprint in the VPS control panel or server console before continuing.",
    "Укажите корректный IP адрес" to "Enter a valid IP address",
    "Укажите корректный IP адрес без протокола" to "Enter a valid IP address without a protocol",
    "Укажите IP, логин и пароль" to "Enter the IP address, login, and password",
    "Введите IP, логин и пароль" to "Enter the IP address, login, and password",
    "Заполните IP, пользователя и пароль" to "Enter the IP address, user, and password",
    "Для установки нужны IP, логин и пароль" to "The IP address, login, and password are required for installation",
    "Этот сервер уже добавлен" to "This server has already been added",
    "Сервер успешно добавлен" to "Server added successfully",
    "Не удалось подключиться к серверу" to "Could not connect to the server",
    "Не удалось получить SSH-ключ сервера" to "Could not retrieve the server SSH key",
    "Не удалось проверить сервер по SSH" to "Could not check the server over SSH",
    "Ошибка SSH-аутентификации: неверный логин или пароль" to "SSH authentication failed: incorrect login or password",
    "Ошибка SSH-аутентификации: неверный логин или пароль." to "SSH authentication failed: incorrect login or password.",
    "Ошибка SSH-сертификата сервера: ключ сервера не прошёл проверку." to "Server SSH key verification failed.",
    "SSH-соединение разорвано сервером во время аутентификации." to "The server closed the SSH connection during authentication.",
    "SSH-ключ сервера ещё не принят. Сначала добавьте сервер и подтвердите его ключ." to "The server SSH key has not been trusted yet. Add the server and confirm its key first.",
    "Не удалось выполнить установку" to "Installation could not be completed",
    "Не удалось установить SymposiumRelay" to "Could not install SymposiumRelay",
    "Не удалось удалить SymposiumRelay" to "Could not remove SymposiumRelay",
    "Не удалось удалить старую версию SymposiumRelay" to "Could not remove the old SymposiumRelay version",
    "Не удалось удалить старую установку" to "Could not remove the old installation",
    "Не удалось установить новую версию SymposiumRelay" to "Could not install the new SymposiumRelay version",
    "SymposiumRelay найден, но его параметры не удалось синхронизировать" to "SymposiumRelay was found, but its settings could not be synchronized",
    "Relay установлен, но приложение не получило TLS pin или adminToken. Переустановите SymposiumRelay." to "Relay was installed, but the app did not receive a TLS pin or admin token. Reinstall SymposiumRelay.",
    "Переустановите SymposiumRelay." to "Reinstall SymposiumRelay.",
    "adminToken отсутствует. Добавьте сервер заново или переустановите SymposiumRelay." to "The admin token is missing. Add the server again or reinstall SymposiumRelay.",
    "TLS pin отсутствует" to "TLS pin is missing",
    "Нет moderator_key" to "moderator_key is missing",
    "Нет TLS pin или moderator_key" to "TLS pin or moderator_key is missing",
    "moderator_key не получен" to "moderator_key was not received",
    "Пересоздайте комнату" to "Recreate the room",
    "Ошибка управления комнатой" to "Room management error",
    "Не удалось обновить открытые комнаты" to "Could not refresh open rooms",
    "Скопировано" to "Copied",
    "QR-код сохранён" to "QR code saved",
    "Не удалось сохранить QR-код" to "Could not save the QR code",
    "Нажмите ещё раз, чтобы выйти" to "Tap again to exit",
    "SymposiumRelay установлен" to "SymposiumRelay installed",
    "Тема" to "Theme",
    "По умолчанию используется тема системы" to "The system theme is used by default",
    "Язык" to "Language",
    "По умолчанию используется язык системы" to "The system language is used by default",
    "Системный" to "System",
    "Использовать язык устройства" to "Use the device language",
    "Русский" to "Russian",
    "Всегда использовать русский язык" to "Always use Russian",
    "Размер шрифта" to "Font size",
    "Системный размер шрифта Android" to "Android system font size",
    "Крупный" to "Large",
    "Крупный шрифт" to "Large font",
    "Очень крупный" to "Extra large",
    "Очень крупный шрифт" to "Extra-large font",
    "Системная" to "System",
    "Использовать тему устройства" to "Use the device theme",
    "Тёмная" to "Dark",
    "Всегда использовать тёмную тему" to "Always use the dark theme",
    "Светлая" to "Light",
    "Всегда использовать светлую тему" to "Always use the light theme",
    "Приватность" to "Privacy",
    "Управление внешней диагностикой" to "External diagnostics settings",
    "Включено" to "On",
    "Выключено" to "Off",
    "Выбрано" to "Selected",
    "Не выбрано" to "Not selected",
    "Развернуто" to "Expanded",
    "Свернуто" to "Collapsed",
    "Анонимная диагностика" to "Anonymous diagnostics",
    "Анонимная диагностика включена" to "Anonymous diagnostics enabled",
    "Анонимная диагностика выключена" to "Anonymous diagnostics disabled",
    "Отправляет только важные технические события: длительность подключения, высокий ping, ошибки WebRTC и итог установки сервера." to "Sends only important technical events: connection duration, high ping, WebRTC errors, and the server installation result.",
    "Персональные данные не отправляются" to "No personal data is sent",
    "Помочь улучшить приложение?" to "Help improve the app?",
    "Дайте Symposium возможность отправлять анонимную диагностику: длительность подключения, ошибки конференции, высокий ping и итог установки сервера." to "Allow Symposium to send anonymous diagnostics: connection duration, conference errors, high ping, and server installation results.",
    "Персональные данные отправляться не будут" to "No personal data will be sent",
    "Независимо от выбора отправляются только факт установки, запуски приложения, ежедневный сигнал установленности и изменения этого разрешения." to "Regardless of your choice, only installation, app launches, a daily installed-app signal, and changes to this permission are sent.",
    "Включить" to "Enable",
    "Панель модерации" to "Moderation panel",
    "Панель модератора" to "Moderator panel",
    "Ожидают входа" to "Waiting to join",
    "В лобби никого нет." to "The lobby is empty.",
    "Участники" to "Participants",
    "Других участников пока нет." to "There are no other participants yet.",
    "Заглушить всех" to "Mute all",
    "Все гости заглушены" to "All guests are muted",
    "Гости могут говорить" to "Guests can speak",
    "Вкл." to "On",
    "Выкл." to "Off",
    "Да" to "Yes",
    "Нет" to "No",
    "Разглушить" to "Unmute",
    "Заглушить" to "Mute",
    "Впустить" to "Admit",
    "Отклонить" to "Reject",
    "Выгнать" to "Remove",
    "Опустить руку" to "Lower hand",
    "Поднять руку" to "Raise hand",
    "✋ Рука" to "✋ Hand raised",
    "Размьют" to "Unmute",
    "Мьют" to "Mute",
    "Скрыть" to "Hide",
    "Перевернуть камеру" to "Switch camera",
    "Уменьшить миниатюру" to "Shrink thumbnail",
    "Увеличить миниатюру" to "Enlarge thumbnail",
    "Выключить камеру" to "Turn camera off",
    "Включить камеру" to "Turn camera on",
    "Микрофон выключен модератором" to "Microphone muted by moderator",
    "Модератор разрешил включить микрофон" to "The moderator allowed you to unmute",
    "Микрофон заблокирован модератором" to "Microphone locked by moderator",
    "Выключить микрофон" to "Mute microphone",
    "Включить микрофон" to "Unmute microphone",
    "Завершить звонок" to "End call",
    "Выбрать вывод звука" to "Choose audio output",
    "Сначала подключитесь к комнате" to "Join a room first",
    "Без разрешения микрофон останется выключенным" to "Without permission, the microphone will remain muted",
    "Без разрешения камера останется выключенной" to "Without permission, the camera will remain off",
    "Разрешение камеры отклонено" to "Camera permission denied",
    "Откройте приложение, чтобы разрешить микрофон" to "Open the app to allow microphone access",
    "Откройте приложение, чтобы разрешить камеру" to "Open the app to allow camera access",
    "Отклонено модератором" to "Rejected by moderator",
    "Удалено модератором" to "Removed by moderator",
    "Неизвестная ошибка" to "Unknown error",
    "неизвестная ошибка" to "unknown error",
    "Готово" to "Done",
    "Ошибка" to "Error",
    "Ошибка SSH: X25519 не поддерживается" to "SSH error: X25519 is not supported",
    "Удаление Relay…" to "Removing Relay…",
    "Relay удалён" to "Relay removed",
    "Очистка…" to "Cleaning up…",
    "SSH готов" to "SSH ready",
    "Найдены старые или неработающие установки; удаление…" to "Old or unhealthy installations found; removing…",
    "Работающая установка найдена; конфигурация синхронизирована" to "A healthy installation was found; configuration synchronized",
    "Не удалось установить маркер установки" to "Could not create the installation marker",
    "Не удалось подготовить каталог установщика" to "Could not prepare the installer directory",
    "Подготовка файлов…" to "Preparing files…",
    "Скрипт" to "Script",
    "Резервный файл сервера" to "Fallback server binary",
    "Скачивание SymposiumRelay из GitHub Release…" to "Downloading SymposiumRelay from the GitHub Release…",
    "GitHub Release недоступен; используется резервный файл из приложения…" to "The GitHub Release is unavailable; using the fallback file bundled with the app…",
    "Повторный запуск с резервным файлом…" to "Retrying with the fallback file…",
    "Во время установки найден существующий сервис; проверка…" to "An existing service was found during installation; checking…",
    "Удаление найденных неработающих установок…" to "Removing unhealthy installations…",
    "Старые установки удалены; повтор чистой установки…" to "Old installations removed; retrying a clean installation…",
    "Установщик не вернул TLS pin" to "The installer did not return a TLS pin",
    "Конфигурация синхронизирована" to "Configuration synchronized",
    "Старые установки удалены" to "Old installations removed",
    "Сервер готов" to "Server ready",
    "1/8 Проверка" to "1/8 Check",
    "2/8 Зависимости" to "2/8 Dependencies",
    "3/8 Файрвол" to "3/8 Firewall",
    "4/8 Токен" to "4/8 Token",
    "5/8 Сервис" to "5/8 Service",
    "6/8 Запуск" to "6/8 Start",
    "8/8 Проверка" to "8/8 Verification",
    "Pin получен" to "Pin received",
    "HTTPS готов" to "HTTPS ready",
    "WSS готов" to "WSS ready",
    "нужны права root" to "root access is required",
    "APT занят другим процессом" to "APT is busy with another process",
    "нет прав доступа" to "permission denied",
    "файл не найден" to "file not found",
    "команда не найдена" to "command not found",
    "ошибка Nginx" to "Nginx error",
    "операция не выполнена" to "operation failed",
    "не удалось скачать файл сервера" to "could not download the server binary"
)

private val englishToRussian = russianToEnglish.entries.associate { (ru, en) -> en to ru } + mapOf(
    "RECONNECTING" to "ПЕРЕПОДКЛЮЧЕНИЕ",
    "Kicked by moderator" to "Удалено модератором",
    "Cancel" to "Отмена",
    "Toggle view" to "Сменить вид",
    "Mic off" to "Микрофон выключен",
    "Mic on" to "Микрофон включён",
    "Muted" to "Заглушён",
    "Open" to "Открыт",
    "Can speak" to "Может говорить",
    "Video on" to "Камера включена",
    "Video off" to "Камера выключена",
    "Pinned" to "Закреплено",
    "English" to "Английский",
    "Always use English" to "Всегда использовать английский язык"
)

internal fun tr(text: String): String {
    if (useRussian()) return englishToRussian[text] ?: text
    russianToEnglish[text]?.let { return it }

    return when {
        text.startsWith("Тема: ") -> "Theme: " + tr(text.removePrefix("Тема: "))
        text.startsWith("Версия: ") -> "Version: " + text.removePrefix("Версия: ")
        text.startsWith("Установленная версия: ") -> "Installed version: " + text.removePrefix("Установленная версия: ")
        text.startsWith("Доступная версия: ") -> "Available version: " + text.removePrefix("Доступная версия: ")
        text.startsWith("Комната: ") -> "Room: " + text.removePrefix("Комната: ")
        text.startsWith("Открытых комнат: ") -> "Open rooms: " + text.removePrefix("Открытых комнат: ")
        text.startsWith("Инфо: ") -> "Info: " + text.removePrefix("Инфо: ")
        text.startsWith("Ошибка установки: ") -> "Installation error: " + text.removePrefix("Ошибка установки: ")
        text.startsWith("Ошибка удаления: ") -> "Removal error: " + text.removePrefix("Ошибка удаления: ")
        text.startsWith("Не удалось удалить старый сервис ") -> "Could not remove the old service " + text.removePrefix("Не удалось удалить старый сервис ")
        text.startsWith("SSH: повтор ") -> "SSH: retry " + text.removePrefix("SSH: повтор ")
        ": повтор " in text -> tr(text.substringBefore(": повтор ")) + ": retry " + text.substringAfter(": повтор ")
        ": с " in text -> tr(text.substringBefore(": с ")) + ": progress " + text.substringAfter(": с ")
        text.startsWith("Установка завершилась с ошибкой") -> text.replace("Установка завершилась с ошибкой", "Installation failed")
        text.startsWith("Обновление завершилось с ошибкой") -> text.replace("Обновление завершилось с ошибкой", "Update failed")
        text.startsWith("Удаление SymposiumRelay завершилось с ошибкой") -> text.replace("Удаление SymposiumRelay завершилось с ошибкой", "SymposiumRelay removal failed")
        text.startsWith("Первое подключение к серверу ") -> text
            .replace("Первое подключение к серверу ", "First connection to server ")
            .replace("Пароль ещё не отправлялся. ", "The password has not been sent yet. ")
            .replace("Если IP указан верно, приложение запомнит fingerprint и предупредит при его изменении.", "If the IP address is correct, the app will remember the fingerprint and warn you if it changes.")
        text.startsWith("Удалить сервер ") -> text
            .replace("Удалить сервер ", "Remove server ")
            .replace(" из приложения? SymposiumRelay на сервере не удаляется.", " from the app? SymposiumRelay will remain on the server.")
        text.startsWith("Комната \"") -> text
            .replace("Комната \"", "Room \"")
            .replace("\" будет закрыта на сервере ", "\" will be closed on server ")
        text.startsWith("Старая ссылка перестанет работать. Активные модераторы комнаты \"") -> text
            .replace(
                "Старая ссылка перестанет работать. Активные модераторы комнаты \"",
                "The old link will stop working. Active moderators in room \""
            )
            .replace(
                "\" будут отключены; гости останутся в комнате.",
                "\" will be disconnected; guests will remain in the room."
            )
        text.startsWith("Старая ссылка комнаты \"") -> text
            .replace("Старая ссылка комнаты \"", "The old link for room \"")
            .replace(
                "\" перестанет работать. Активные модераторы будут отключены; гости останутся в комнате.",
                "\" will stop working. Active moderators will be disconnected; guests will remain in the room."
            )
        text.startsWith("Актуальная · ") -> "Up to date · " + text.removePrefix("Актуальная · ")
        text.startsWith("Не актуальна · ") -> "Outdated · " + text.removePrefix("Не актуальна · ")
        text.startsWith("В лобби: ") -> text
            .replace("В лобби: ", "Lobby: ")
            .replace(" · Участников: ", " · Participants: ")
            .replace(" · Руки: ", " · Hands: ")
        text.endsWith(" ждёт входа") -> text.removeSuffix(" ждёт входа") + " is waiting to join"
        text.endsWith(" ждут входа") -> text.removeSuffix(" ждут входа") + " are waiting to join"
        else -> text
    }
}

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    MaterialText(
        text = tr(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}
