import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
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

fun gitOutput(vararg args: String): String? = runCatching {
    val output = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()
    output.takeIf { it.isNotBlank() }
}.getOrNull()
val gitVersionName = gitOutput("describe", "--tags", "--always", "--dirty")
    ?: "0.1.0"
// versionCode tracks master commit count at the branch point so feature-branch
// commits do not bump it (avoids install conflicts across branches).
val gitVersionCodeBase = sequenceOf("master", "origin/master")
    .mapNotNull { ref -> gitOutput("merge-base", "HEAD", ref) }
    .firstOrNull()
    ?: "HEAD"
val gitVersionCode = gitOutput("rev-list", "--count", gitVersionCodeBase)
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
android {
    namespace = "org.feeluown.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.feeluown.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    sourceSets {
        getByName("main").assets.srcDir(
            rootProject.file("shared/src/commonMain/resources"),
        )
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

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:model"))
    implementation(project(":playback:api"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material3.expressive)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lyricon.provider)
}
