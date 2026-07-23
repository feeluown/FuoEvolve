import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquopy)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(envName: String, propertyName: String): String? =
    providers.environmentVariable(envName).orNull
        ?: localProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val localBuildPython = "/home/bruce/.local/bin/python3.12"
val configuredBuildPython = providers.environmentVariable("FUO_BUILD_PYTHON").orNull
    ?: localBuildPython.takeIf { file(it).exists() }
val pypiFeelUOwnSource = "https://files.pythonhosted.org/packages/b2/41/c0f205f279e7bc5e1441d65679f693133dcac976b59ff14f3a1adf9e168d/feeluown-5.1.2.tar.gz"
val feelUOwnSource = providers.environmentVariable("FUO_FEELUOWN_SOURCE").orNull
    ?: pypiFeelUOwnSource
fun gitOutput(vararg args: String): String? = runCatching {
    val output = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()
    output.takeIf { it.isNotBlank() }
}.getOrNull()
val gitVersionName = gitOutput("describe", "--tags", "--always", "--dirty")
    ?: "0.1.0"
val gitVersionCode = gitOutput("rev-list", "--count", "HEAD")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: 1
val fuoSigningStoreFile = signingValue("FUO_SIGNING_STORE_FILE", "fuo.signing.storeFile")
val fuoSigningStorePassword = signingValue("FUO_SIGNING_STORE_PASSWORD", "fuo.signing.storePassword")
val fuoSigningKeyAlias = signingValue("FUO_SIGNING_KEY_ALIAS", "fuo.signing.keyAlias")
val fuoSigningKeyPassword = signingValue("FUO_SIGNING_KEY_PASSWORD", "fuo.signing.keyPassword")
val hasFuoSigningConfig = listOf(
    fuoSigningStoreFile,
    fuoSigningStorePassword,
    fuoSigningKeyAlias,
    fuoSigningKeyPassword,
).all { !it.isNullOrBlank() }
val releasePerAbiEnabled = providers.gradleProperty("fuo.releasePerAbi")
    .map(String::toBoolean)
    .orElse(false)
    .get()

android {
    namespace = "org.feeluown.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.feeluown.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        if (!releasePerAbiEnabled) {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(
            rootProject.file("shared/src/commonMain/resources"),
        )
    }

    if (releasePerAbiEnabled) {
        flavorDimensions += "pythonAbi"
        productFlavors {
            create("arm64") {
                dimension = "pythonAbi"
                ndk { abiFilters += "arm64-v8a" }
            }
            create("x86") {
                dimension = "pythonAbi"
                ndk { abiFilters += "x86_64" }
            }
            create("universal") {
                dimension = "pythonAbi"
                ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable.add("NullSafeMutableLiveData")
    }

    signingConfigs {
        create("fuo") {
            if (hasFuoSigningConfig) {
                storeFile = rootProject.file(fuoSigningStoreFile!!)
                storePassword = fuoSigningStorePassword
                keyAlias = fuoSigningKeyAlias
                keyPassword = fuoSigningKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (hasFuoSigningConfig) {
                signingConfigs.getByName("fuo")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasFuoSigningConfig) {
                signingConfig = signingConfigs.getByName("fuo")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    sourceSets {
        getByName("main") {
            srcDir(rootProject.file("shared/src/commonMain/python"))
        }
    }

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
            install("beautifulsoup4")
            install("cachetools")
            install("certifi")
            install("charset-normalizer")
            install("idna")
            install("janus")
            install("marshmallow==3.26.2")
            install("mutagen")
            install("packaging")
            install("pydantic==1.10.26")
            install("pycryptodome==3.21.0")
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
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
    implementation(libs.kotlinx.coroutines.android)
}
