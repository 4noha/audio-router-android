// Top-level build script. アプリ固有設定は app/build.gradle.kts。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // App Tester (Firebase App Distribution) 配信。`./gradlew :app:appDistributionUploadDebug`
    id("com.google.firebase.appdistribution") version "5.0.0" apply false
}
