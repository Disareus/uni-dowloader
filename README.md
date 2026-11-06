# AniUA Downloader для Android

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Media3](https://img.shields.io/badge/ExoPlayer-Media3%201.3.1-FF5722.svg)](https://developer.android.com/media/media3)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Сучасний нативний Android-додаток, написаний на **100% Kotlin**, для пошуку, аналізу, перегляду та швидкого завантаження аніме, серіалів і фільмів з українських медіа-ресурсів (**UAKino**, **AniTube**) та популярних відеобалансерів (**Fenix Player**, **Ashdi**, **Tortuga**, **Moon**, **PeerTube**, **Sibnet**).

---

## 🌟 Основні можливості

- **100% Kotlin & Coroutines**: Чиста реактивна архітектура на базі `StateFlow`, `lifecycleScope` та структурованого паралелізму без блокування UI.
- **Підтримка відеобалансерів**:
  - 🪶 **Fenix Player (`fenixplay.xyz`)**: Повна інтеграція з Playback API, авторизація JWT-токенів доступу до CDN, підтримка fragmented MP4 (fMP4) та автоматичне апаратне склеювання розділених аудіо- та відео-доріжок через `MediaMuxer`.
  - 📺 **PeerTube (`peertube.in.ua`, інстанси Fediverse)**: Підтримка PeerTube REST API v1, вилучення multi-variant HLS master-потоків з розділеними аудіо-доріжками та прямих MP4-файлів.
  - 🌙 **Moon (`moonanime.net`, `moonanime.art`)**: Двоступеневе декодування, витягування HLS мастер-плейлистів із кастомними заголовками.
  - ⚡ **Ashdi**: Автоматичне видобування та розгортання multi-variant HLS потоків.
  - 🐢 **Tortuga**: Розкодування прямих потоків плейлистів.
  - 🌐 **Sibnet**: Прямий стрімінг та завантаження MP4-відео.
- **HLS Segment Downloader із розшифруванням AES-128**:
  - Багатопотокове скачування сегментів `.ts` та `.m4s`.
  - Автоматичне розшифрування потоків із шифруванням AES-128 CBC/PKCS7 на льоту.
  - Підтримка ініціалізаційних заголовків fMP4 (`#EXT-X-MAP`).
  - Захист від завантаження битих HTML-сторінок або незавершених сесій (fail-fast перевірка MIME та розміру).
- **Організація завантажень за папками**:
  - Кожне аніме автоматично зберігається у власну підпапку з назвою студії озвучення:
    `Movies/AniUA/{Назва Аніме} [{Студія озвучення}]/{Серія}.mp4`
  - Повна сумісність зі Scoped Storage (Android 10–15) через MediaStore API.
- **Вбудований відеоплеєр (ExoPlayer Media3)**:
  - Прямий перегляд серій безпосередньо у додатку.
  - Адаптивне перемикання бітрейту та вибір якості відео (1080p, 720p, 480p, 360p).
  - Підтримка кастомних реферерів та User-Agent для захищених CDN.
- **Темний інтерфейс у стилі Material 3**:
  - Адаптований під OLED-екрани інтерфейс.
  - Зручний вибір студій озвучення через інтерактивні чипи.
  - Пакетне завантаження серій («Вибрати всі» / окремі епізоди).
  - Моніторинг швидкості та прогресу у реальному часі.

---

## 🛡️ Комплексний модуль безпеки (`:security`)

Додаток оснащений автономною підсистемою захисту та харденінгу:

1. **Багаторівнева модель реагування (Tiered Threat Model)**:
   - **CRITICAL_ATTACK**: Миттєве блокування при виявленні налагоджувачів (`Debug.isDebuggerConnected()`, `TracerPid`), хукінг-фреймворків (**Frida**, **Xposed**, **LSPosed**) або підробки цифрового підпису APK.
   - **ENVIRONMENT_RISK**: Захищений режим роботи на рутованих пристроях (Magisk, KernelSU, APatch) або емуляторах із ізоляцією кешу.
2. **Мережевий захист**:
   - Повна заборона незахищеного Cleartext HTTP трафіку (`cleartextTrafficPermitted="false"`).
   - Захист від витоку авторизаційних токенів при зверненні до зовнішніх CDN (`SecurityHeadersInterceptor`).
   - Підтримка безпечного DNS-over-HTTPS (DoH) через Cloudflare та Google DNS.
3. **Криптографія та Сховище**:
   - Апаратне шифрування AES-256-GCM із використанням **AndroidKeyStore** (StrongBox / TEE).
   - Захист від атак Path Traversal при збереженні файлів (перевірка канонічних шляхів та нормалізація Unicode).

---

## 📁 Структура проєкту

```text
AniUaDownloader/
├── app/                                 # Головний модуль Android-додатку
│   ├── src/main/java/ua/aniua/downloader/
│   │   ├── extractor/                   # Парсери сайтів і відеобалансерів
│   │   │   ├── AnitubeExtractor.kt      # Парсер AniTube
│   │   │   ├── UaKinoExtractor.kt       # Парсер UAKino
│   │   │   ├── FenixExtractor.kt        # Екстрактор Fenix Player
│   │   │   ├── PeerTubeExtractor.kt     # Екстрактор PeerTube
│   │   │   ├── AshdiExtractor.kt        # Екстрактор Ashdi
│   │   │   ├── TortugaExtractor.kt      # Екстрактор Tortuga
│   │   │   ├── MoonAnimeExtractor.kt    # Екстрактор Moon/MoonAnime
│   │   │   ├── GenericPlayerResolver.kt # Диспетчер резолву потоків
│   │   │   └── HlsStreamResolver.kt     # Парсер HLS master-плейлистів
│   │   ├── model/                       # Data-класи моделей даних
│   │   ├── service/                     # Фонова служба та HlsSegmentDownloader
│   │   └── ui/                          # Activities, Adapters, Views
│   └── src/main/res/                    # Списки ресурсів, теми та розмітка
├── security/                            # Окремий модуль безпеки та криптографії
│   └── src/main/java/ua/security/
│       ├── crypto/                      # Апаратний Keystore та SecureStorage
│       ├── hardening/                   # Anti-Root, Anti-Debug, Anti-Frida
│       ├── network/                     # DoH, безпечні перехоплювачі заголовків
│       └── storage/                     # Scoped Storage безпека та MediaStore
└── .github/workflows/                   # GitHub Actions автоматична збірка (CI)
```

---

## 🚀 Як зібрати проєкт

### Вимоги:
- **JDK 17** (або новіша, наприклад JetBrains Runtime / OpenJDK 17)
- **Android SDK** (API 34, Build Tools 34.0.0)

### Збірка з командного рядка:
```bash
# Клонування репозиторію
git clone https://github.com/<ваш-профіль>/AniUaDownloader.git
cd AniUaDownloader

# Збірка Debug APK
./gradlew assembleDebug
```
Готовий APK буде створено за шляхом:
`app/build/outputs/apk/debug/app-debug.apk`

### Запуск в Android Studio:
1. Запустіть **Android Studio**.
2. Оберіть **File** ➔ **Open...** та виберіть папку `AniUaDownloader`.
3. Дочекайтеся завершення **Gradle Sync**.
4. Виберіть конфігурацію запуску **app** та натисніть кнопку **Run (▶)**.

---

## 📄 Ліцензія

Цей проєкт поширюється під ліцензією [MIT](LICENSE).
