plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquopy)
}

val localBuildPython = "/home/bruce/.local/bin/python3.12"
val configuredBuildPython = providers.environmentVariable("FUO_BUILD_PYTHON").orNull
    ?: localBuildPython.takeIf { file(it).exists() }
val pypiFeelUOwnSource = "https://files.pythonhosted.org/packages/b2/41/c0f205f279e7bc5e1441d65679f693133dcac976b59ff14f3a1adf9e168d/feeluown-5.1.2.tar.gz"
val feelUOwnSource = providers.environmentVariable("FUO_FEELUOWN_SOURCE").orNull
    ?: pypiFeelUOwnSource

android {
    namespace = "org.feeluown.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.feeluown.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        configuredBuildPython?.let { buildPython(it) }
        pip {
            options("--no-deps")
            install(feelUOwnSource)
            install("https://files.pythonhosted.org/packages/1c/70/cf356c9096d401ad63acbe686b51f1d50d491e9afb478e704c574ab17606/fuo_netease-1.0.8.tar.gz")
            install("fuo-qqmusic==1.0.16")
            install("feeluown-bilibili==0.5.5")
            install("fuo-ytmusic==0.4.18")
            install("attrs")
            install("beautifulsoup4")
            install("babel")
            install("cachetools")
            install("certifi")
            install("charset-normalizer")
            install("fluent-runtime==0.4.0")
            install("fluent.syntax")
            install("idna")
            install("janus")
            install("marshmallow==3.26.2")
            install("mutagen")
            install("packaging")
            install("pydantic==1.10.26")
            install("pycryptodome==3.21.0")
            install("pytz")
            install("qasync")
            install("requests")
            install("soupsieve")
            install("tomlkit")
            install("typing-extensions")
            install("urllib3")
            install("yt-dlp")
            install("ytmusicapi")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
    implementation(libs.kotlinx.coroutines.android)
}
