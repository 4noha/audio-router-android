import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.firebase.appdistribution")
}

// Firebase App Distribution（= AppTester 配信）。babigoe-kai と同じ流儀。
// App ID・配信先・service account JSON はコミット対象に出さない。環境変数 →
// local.properties（git 管理外）→ -P の順に読む。未設定でもビルドは通り、
// 配信タスク（appDistributionUpload*）だけが失敗する。
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun distSetting(env: String, key: String): String? =
    System.getenv(env) ?: localProps.getProperty(key) ?: (project.findProperty(key) as String?)
val fbAppId: String = distSetting("FIREBASE_APP_ID", "firebaseAppId") ?: ""
val fbTesters: String = distSetting("FIREBASE_TESTERS", "firebaseTesters") ?: ""
val fbCreds: String = distSetting("FIREBASE_APP_DISTRIBUTION_CREDS", "firebaseCreds")
    ?: "${rootDir}/firebase-appdistribution.json"

android {
    namespace = "com.fournoha.audiorouter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fournoha.audiorouter"
        // minSdk 29 (Android 10) = MediaCodec の Opus サポートに必要。
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.1"
    }

    buildTypes {
        debug {
            // ./gradlew :app:assembleDebug appDistributionUploadDebug
            firebaseAppDistribution {
                appId = fbAppId
                artifactType = "APK"
                testers = fbTesters
                releaseNotesFile = "${rootDir}/release-notes.txt"
                serviceCredentialsFile = fbCreds
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            firebaseAppDistribution {
                appId = fbAppId
                artifactType = "APK"
                testers = fbTesters
                releaseNotesFile = "${rootDir}/release-notes.txt"
                serviceCredentialsFile = fbCreds
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
