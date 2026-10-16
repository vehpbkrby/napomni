import java.util.Properties

// Постоянный ключ подписи лежит вне git: keystore.properties + release.keystore в корне проекта.
// Без него обновления не встанут поверх уже установленного приложения.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.napomni.app"
    compileSdk = 35
    // Явно — те, что уже есть в образе сборки (иначе плагин докачивает другие с Google при каждой сборке).
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ru.napomni.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        create("release") {
            if (!keystoreProps.isEmpty) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isEmpty) {
                logger.warn("keystore.properties не найден: release подписан отладочным ключом")
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
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
        compose = true
    }

    lint {
        // lint тянет ~100 МБ библиотек с Google Maven, который из РФ отдаёт медленно.
        checkReleaseBuilds = false
    }

    testOptions {
        // org.json в модульных тестах — заглушка Android; хранилище в тестах не трогаем.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Проверка расчётов без телефона: gradle testReleaseUnitTest
    testImplementation("junit:junit:4.13.2")
}
