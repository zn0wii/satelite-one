import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// signing.properties (gitignored) — release builds fall back to debug signing
// when absent so the project still builds on fresh checkouts.
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// version.properties — 全局唯一的版本定义处 (设置页与 CI 的 tag 校验都依赖它)
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.interstellar.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.interstellar.proxy"
        minSdk = 24
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    signingConfigs {
        create("release") {
            if (signingProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // debug builds get their own applicationId so 地心游记 (debug) and
        // 星际穿越 (release) coexist on the same device
        debug {
            applicationIdSuffix = ".debug"
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // per-ABI APKs: ~40MB instead of one 146MB universal blob
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        aidl = true
        // 设置页"版本"行读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
}

// APK 输出统一以 satelite-one 开头：satelite-one-<abi>-<buildType>.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            output.outputFileName.set("satelite-one-${abi ?: "universal"}-${variant.name}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // sing-box core (built via `make lib_android` from the sing-box source)
    if (File(projectDir, "libs/libbox.aar").exists()) {
        implementation(files("libs/libbox.aar"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // real org.json for JVM tests — XrayConfigBuilder builds on it and the
    // SDK stub throws "not mocked"
    testImplementation("org.json:json:20240303")
}
