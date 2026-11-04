# ==============================================================================
# PROGUARD / R8 OBFUSCATION & HARDENING RULES ДЛЯ ANDROID-ДОДАТКУ ANIUA
# Комплексна оптимізація, агресивна обфускація та видалення налагоджувального коду
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Загальні налаштування оптимізації та стискання коду (Shrinking & Optimization)
# ------------------------------------------------------------------------------
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# Перепакування всіх внутрішніх обфускованих класів в один плоский анонімний пакет
-repackageclasses 'ua.security.internal'

# Видалення оригінальних імен вихідних файлів та номерів рядків для ускладнення декомпіляції
-renamesourcefileattribute 'SourceFile'
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod

# ------------------------------------------------------------------------------
# 2. Повне видалення налагоджувальних логів та трасувань (Log Stripping)
# ------------------------------------------------------------------------------
# R8 автоматично видаляє виклики цих методів під час збірки Release APK
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Видалення логування HTTP запитів і відповідей у продакшні
-assumenosideeffects class okhttp3.logging.HttpLoggingInterceptor {
    public void log(...);
}

# ------------------------------------------------------------------------------
# 3. Правила для модулів безпеки (ua.security.**)
# ------------------------------------------------------------------------------
# Обфускуємо внутрішні алгоритми перевірок, імена змінних та допоміжні методи.
# Залишаємо публічні точки входу для інтеграції між модулями.
-keep class ua.security.hardening.AppSecurityManager {
    public static ua.security.hardening.AppSecurityManager getInstance(android.content.Context);
    public ua.security.hardening.SecurityAuditResult performFullAudit();
    public boolean runProactiveSecurityCheck();
    public void startPeriodicSecurityMonitor(long);
    public void stopPeriodicSecurityMonitor();
    public void handleCriticalBreach(java.lang.String);
}

-keep class ua.security.hardening.ThreatLevel { *; }
-keep interface ua.security.hardening.SecurityListener { *; }
-keep class ua.security.hardening.SecurityAuditResult { *; }

-keep class ua.security.crypto.SecureStorageManager {
    public static ua.security.crypto.SecureStorageManager getInstance(android.content.Context);
    public void emergencyWipe();
    public void saveAuthToken(java.lang.String);
    public java.lang.String getAuthToken();
    public void clearSession();
}

-keep class ua.security.crypto.KeystoreCryptoManager {
    public static ua.security.crypto.KeystoreCryptoManager getInstance();
    public byte[] encrypt(byte[], byte[]);
    public byte[] decrypt(byte[], byte[]);
    public void deleteMasterKey();
}

-keep class ua.security.network.NetworkSecurityManager {
    public static ua.security.network.NetworkSecurityManager getInstance(android.content.Context, ua.security.network.TamperDetectionInterceptor$SecurityStateProvider);
    public okhttp3.OkHttpClient getOkHttpClient();
}

-keep interface ua.security.network.TamperDetectionInterceptor$SecurityStateProvider { *; }

-keep class ua.security.storage.DownloadSecurityManager {
    public static ua.security.storage.DownloadSecurityManager getInstance(android.content.Context);
    public java.io.File prepareSafeDownloadFile(java.lang.String, java.lang.String, boolean);
    public boolean verifyPathWithinDirectory(java.io.File, java.io.File);
}

# ------------------------------------------------------------------------------
# 4. Правила для Android компонентів та системних класів
# ------------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.preference.Preference

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ------------------------------------------------------------------------------
# 5. Підтримка JNI та Native-методів (для майбутніх .so захисних модулів)
# ------------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ------------------------------------------------------------------------------
# 6. Мережевий стек: OkHttp, Okio та DNS-over-HTTPS
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------------------------------
# 7. Медіаплеєр (ExoPlayer / AndroidX Media3) для відтворення відео/аніме
# ------------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ------------------------------------------------------------------------------
# 8. Серіалізація даних (Gson / Moshi / Моделі парсерів)
# ------------------------------------------------------------------------------
-keepattributes *Annotation*
-keepclassmembers enum * { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class ua.aniua.downloader.models.** { *; }
