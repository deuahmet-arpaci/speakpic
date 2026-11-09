plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Play Store'a yüklenecek paketin (AAB) imzası için bilgiler ortam değişkenlerinden okunur.
// Değişkenler yoksa (ör. normal debug derleme) imzalama atlanır.
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.speakpic.app"
    compileSdk = 36

    defaultConfig {
        // DİKKAT: Play Store'a ilk yüklemeden sonra applicationId ASLA değiştirilemez.
        applicationId = "io.github.deuahmetarpaci.speakpic"
        minSdk = 30
        targetSdk = 36
        // CI'da her derlemede otomatik artar (Play her yüklemede daha büyük versionCode ister).
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            if (!signingKeystorePath.isNullOrBlank()) {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!signingKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Uygulama içi dil seçici için tüm dillerin pakette kalması şart:
    // aksi halde Play, yalnızca cihaz dilini indirir ve dil değiştirme çalışmaz.
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Değişken hızlı video dışa aktarma (EditedMediaItem.setSpeed ile SpeedProvider, 1.9.0'dan beri)
    val media3Version = "1.9.3"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
}
