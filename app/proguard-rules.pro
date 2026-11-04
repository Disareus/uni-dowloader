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

-assumenosideeffects class okhttp3.logging.HttpLoggingInterceptor {
    public void log(...);
}

# ------------------------------------------------------------------------------
# 3. Правила для модулів безпеки (ua.security.**)
# ------------------------------------------------------------------------------
-keep class ua.security.hardening.AppSecurityManager {
    public static ua.security.hardening.AppSecurityManager getInstance(android.content.Context);
    public ua.security.hardening.SecurityAuditResult performAudit();
    public void startPeriodicAudit(int);
    public void registerListener(ua.security.hardening.SecurityListener);
}

-keep class ua.security.hardening.SecurityAuditResult {
    public *;
}

-keep class ua.security.hardening.ThreatLevel {
    public *;
}

-keep interface ua.security.hardening.SecurityListener {
    public *;
}

-keep class ua.security.network.NetworkSecurityManager {
    public static ua.security.network.NetworkSecurityManager getInstance(android.content.Context);
    public okhttp3.OkHttpClient getHardenedHttpClient();
}

-keep class ua.security.crypto.SecureStorageManager {
    public static ua.security.crypto.SecureStorageManager getInstance(android.content.Context);
    public void emergencyWipe();
}

-keep class ua.security.storage.DownloadSecurityManager {
    public static ua.security.storage.DownloadSecurityManager getInstance(android.content.Context);
    public java.lang.String sanitizeFileName(java.lang.String);
    public android.net.Uri createScopedVideoUri(java.lang.String, java.lang.String);
    public java.io.File getGuardedStorageDir();
    public void validateCanonicalDestination(java.io.File, java.io.File);
    public void publishPendingVideo(android.net.Uri);
}

# ------------------------------------------------------------------------------
# 4. Правила для моделей даних та Gson/JSON серіалізації
# ------------------------------------------------------------------------------
-keepclassmembers class ua.aniua.downloader.model.** {
    <fields>;
    <methods>;
}

# ------------------------------------------------------------------------------
# 5. Сторонні бібліотеки: OkHttp, Media3 ExoPlayer, Glide, Jsoup
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
