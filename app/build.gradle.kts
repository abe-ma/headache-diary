import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// applicationId / namespace (確定値)。変更する場合はここと src 配下のパッケージディレクトリ・package 宣言を揃える。
val appId = "io.github.abe_ma.headachediary"
val appVersionName = "0.2.0"

// release 署名情報 (Git 管理外)。app/keystore.properties (無ければ rootProject 直下) が存在するときだけ署名する。
// 無ければ従来どおり署名なしでビルドが通る (CI / 他人のクローン向け)。雛形は keystore.properties.example。
// 場所を明示する場合: ./gradlew -PkeystoreProperties=/絶対パス/keystore.properties assembleRelease (別ディレクトリでのビルド用)
val keystoreFile = (findProperty("keystoreProperties") as String?)?.let { file(it) }?.takeIf { it.isFile }
    ?: listOf(file("keystore.properties"), rootProject.file("keystore.properties")).firstOrNull { it.isFile }
val keystoreProps = Properties().apply { keystoreFile?.inputStream()?.use { load(it) } }
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
if (keystoreFile != null) {
    val missing = signingKeys.filter { keystoreProps.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        // 中途半端な設定のまま署名なし APK を作らない。値は出力せず、キー名だけ示す。
        throw GradleException("keystore.properties に未設定のキーがあります: $missing")
    }
}
val hasReleaseSigning = keystoreFile != null

// 出力名: headache-diary-<versionName>-<buildType>.apk (release 署名付きは headache-diary-0.2.0-release.apk)
base.archivesName.set("headache-diary-$appVersionName")

android {
    namespace = appId
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // "~/" 始まりは home に展開 (例: ~/.android-keys/headache-diary-release.jks)
                storeFile = file(keystoreProps.getProperty("storeFile").replaceFirst(Regex("^~"), System.getProperty("user.home")))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 26: v2 以上が有効。v1 は不要 (API 24+ で v2 検証)。AGP が v2/v3 を付与する。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric 用
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // Room スキーマ (exportSchema=true)。DB 移行テストで使うためコミットする。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Open-Meteo 用 HTTP クライアント
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
