# AniUA Downloader для Android (Java)

Нативний Android-додаток на мові **Java** для пошуку, аналізу та завантаження серіалів, аніме та фільмів з українських медіа-ресурсів (**UAKino**, **Anitube**), балансерів (**Ashdi**, **Tortuga**, **MoonAnime**) та прямого перегляду через вбудований медіаплеєр **ExoPlayer (Media3)**.

---

## 1. Архітектура проєкту

Проєкт побудований за модульною схемою:
- **`:app`** — головний модуль застосунку (UI на Material 3, RecyclerView, фонова служба завантаження `DownloadService`, медіаплеєр `PlayerActivity`, парсери `ExtractorRouter`, `UaKinoExtractor`, `AnitubeExtractor`, `AshdiExtractor`).
- **`:security`** — окрема бібліотека захисту та безпеки, спроектована та погоджена двома автономними суб-агентами безпеки (`SecurityArchitectAgent` та `CryptoNetworkSecurityAgent`).

---

## 2. Комплексна підсистема захисту (Security & App Hardening)

### 2.1. Багаторівнева модель реагування (Tiered Risk Model)
- **Рівень 2 (CRITICAL_ATTACK)**:
  - *Тригери*: активний налагоджувач (`Debug.isDebuggerConnected()`, `TracerPid > 0`), фреймворки динамічного аналізу (**Frida**, **Xposed**, **LSPosed**), підміна цифрового підпису APK (`IntegrityDetector`).
  - *Реакція*: **Fail-Closed**. Мережевий стек `TamperDetectionInterceptor` обриває всі з'єднання фіктивною системною помилкою (`Connection reset by peer`), `SecureStorageManager` занулює буфери в RAM і безповоротно видаляє майстер-ключ із апаратного чіпа `AndroidKeyStore`, процес додатку негайно самоліквідується (`Process.killProcess()`).
- **Рівень 1 (ENVIRONMENT_RISK)**:
  - *Тригери*: наявність `su` бінарників, Magisk / KernelSU / APatch, RW-монтування `/system`, `test-keys`, емулятори (QEMU, BlueStacks, LDPlayer).
  - *Реакція*: **Guarded Mode**. Додаток зберігає працездатність для легітимних power-users, але ізолює всі завантажені файли у захищений App-Specific Sandbox (`context.getExternalFilesDir()`) із забороною незашифрованого спільного експорту.

### 2.2. Мережева безпека та захист від перехоплення
1. **Network Security Config** (`network_security_config.xml`):
   - Повна заборона Cleartext HTTP трафіку (`cleartextTrafficPermitted="false"`).
   - Довіра виключно системним сертифікатам (`system CAs`). Користувацькі сертифікати (Burp Suite, Charles, mitmproxy) автоматично відкидаються ОС Android.
2. **Двоешелонний Certificate Pinning (Dual-Layer Pinning)**:
   - *Ешелон 1*: Офіційний `okhttp3.CertificatePinner`.
   - *Ешелон 2*: Кастомний `DualLayerPinningInterceptor` перевіряє SPKI SHA-256 хеш безпосередньо з TLS `Handshake`, що унеможливлює обхід стандартними публічними скриптами Frida.
3. **DNS-over-HTTPS (DoH)**:
   - Безпечний резолв доменів через Cloudflare (1.1.1.1) та Google (8.8.8.8) з прямими bootstrap-IP для захисту від блокувань провайдерами (ISP Spoofing / DNS Poisoning).
4. **Захист від витоку токенів**:
   - `SecurityHeadersInterceptor` примусово знімає чутливі авторизаційні заголовки при переході на зовнішні відеохостинги та генерує валідні `Referer` і `User-Agent`.

### 2.3. Криптографія та сховище
- **Апаратний Keystore**: `AES-256-GCM` із пріоритетом на апаратний чіп **StrongBox Keymaster** та fallback на TEE.
- **EncryptedSharedPreferences**: безпечне зберігання налаштувань та сесій.
- **Захист від Path Traversal**: `DownloadSecurityManager` нормалізує Unicode (NFKC), санітизує імена файлів від `..`, нуль-байтів та перевіряє канонічний шлях (`canonicalPath.startsWith(canonicalBase)`).

---

## 3. Як відкрити та запустити в Android Studio

1. Відкрийте **Android Studio** (Hedgehog, Iguana, Jellyfish або новішу).
2. Оберіть **File -> Open...** та виберіть папку `downloadvideos/android`.
3. Дочекайтеся завершення Gradle Sync.
4. Оберіть конфігурацію запуску **app** та запустіть на пристрої або емуляторі (Run ▶).
