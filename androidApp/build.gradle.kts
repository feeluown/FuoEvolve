plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquopy)
}

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
        buildPython("/home/bruce/.local/bin/python3.12")
        pip {
            install("/home/bruce/Projects/Python/FeelUOwn")
            install("https://files.pythonhosted.org/packages/1c/70/cf356c9096d401ad63acbe686b51f1d50d491e9afb478e704c574ab17606/fuo_netease-1.0.8.tar.gz")
            install("fuo-qqmusic==1.0.16")
            install("beautifulsoup4")
            install("marshmallow")
            install("mutagen")
            install("pycryptodome")
            install("requests")
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
    implementation(libs.kotlinx.coroutines.android)
}
