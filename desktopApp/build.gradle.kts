import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3.expressive)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.org.json)
}

compose.desktop {
    application {
        mainClass = "org.feeluown.mobile.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "FuoEvolve"
            packageVersion = "0.1.0"
            description = "FeelUOwn-based Compose Multiplatform music player"
            vendor = "FeelUOwn"
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            linux {
                packageName = "fuo-evolve"
                appCategory = "Audio"
                menuGroup = "Audio"
                iconFile.set(layout.projectDirectory.file("../androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
            }
        }
    }
}

val desktopPythonDir = layout.projectDirectory.dir("../.gradle/desktop-python")
val desktopBridgeScript = layout.projectDirectory.file("src/main/python/desktop_bridge.py")
val androidPythonDir = layout.projectDirectory.dir("../shared/src/commonMain/python")
val feelUOwnSource = providers.environmentVariable("FUO_FEELUOWN_SOURCE")
    .orElse("https://files.pythonhosted.org/packages/b2/41/c0f205f279e7bc5e1441d65679f693133dcac976b59ff14f3a1adf9e168d/feeluown-5.1.2.tar.gz")

tasks.register("prepareDesktopPython") {
    val marker = desktopPythonDir.file("install.marker")
    outputs.file(marker)
    doLast {
        fun runCommand(vararg args: String) {
            val exitCode = ProcessBuilder(*args)
                .inheritIO()
                .start()
                .waitFor()
            check(exitCode == 0) { "Command failed (${args.joinToString(" ")}): exit=$exitCode" }
        }

        val venvDir = desktopPythonDir.asFile
        val python = providers.environmentVariable("FUO_BUILD_PYTHON").orNull ?: "python3"
        val pip = venvDir.resolve("bin/pip")
        if (!pip.isFile) {
            venvDir.deleteRecursively()
            runCommand(python, "-m", "venv", venvDir.absolutePath)
        }
        runCommand(pip.absolutePath, "install", "--upgrade", "pip")
        runCommand(
            pip.absolutePath,
            "install",
            "--no-deps",
            feelUOwnSource.get(),
            "https://files.pythonhosted.org/packages/1c/70/cf356c9096d401ad63acbe686b51f1d50d491e9afb478e704c574ab17606/fuo_netease-1.0.8.tar.gz",
            "fuo-qqmusic==1.0.16",
            "feeluown-bilibili==0.5.5",
            "fuo-ytmusic==0.4.18",
            "attrs",
            "beautifulsoup4",
            "babel",
            "cachetools",
            "certifi",
            "charset-normalizer",
            "fluent-runtime==0.4.0",
            "fluent.syntax",
            "idna",
            "janus",
            "marshmallow==3.26.2",
            "mutagen",
            "packaging",
            "pydantic==1.10.26",
            "pycryptodome==3.21.0",
            "pytz",
            "qasync",
            "requests",
            "soupsieve",
            "tomlkit",
            "typing-extensions",
            "urllib3",
            "yt-dlp",
            "ytmusicapi",
        )
        marker.asFile.writeText("ok\n")
    }
}

val syncDesktopAppResources = tasks.register<Sync>("syncDesktopAppResources") {
    dependsOn("prepareDesktopPython")
    into(layout.buildDirectory.dir("app-resources/common"))
    from(desktopBridgeScript) {
        into("desktop-python/bridge")
    }
    from(androidPythonDir) {
        into("desktop-python/android-python")
        exclude("**/__pycache__/**")
        exclude("**/*.pyc")
        exclude("**/*.iml")
    }
    from(desktopPythonDir) {
        into("desktop-python/venv")
        exclude("**/__pycache__/**")
        exclude("**/*.pyc")
        exclude("bin/𝜋thon")
    }
}

val packageAppImageFile = tasks.register<Exec>("packageAppImageFile") {
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/FuoEvolve")
    val appDir = layout.buildDirectory.dir("appimage/FuoEvolve.AppDir")
    val artifact = layout.buildDirectory.file("artifacts/FuoEvolve.AppImage")
    val appImageTool = layout.buildDirectory.file("appimagetool-x86_64.AppImage")
    val iconFile = layout.projectDirectory.file("../androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png")

    dependsOn("packageAppImage")
    inputs.dir(appImageDir)
    inputs.file(iconFile)
    outputs.file(artifact)
    commandLine(
        "bash",
        "-c",
        listOf(
            "set -euo pipefail",
            "app_image_dir=\"${appImageDir.get().asFile.absolutePath}\"",
            "app_dir=\"${appDir.get().asFile.absolutePath}\"",
            "artifact=\"${artifact.get().asFile.absolutePath}\"",
            "appimagetool=\"${appImageTool.get().asFile.absolutePath}\"",
            "icon_file=\"${iconFile.asFile.absolutePath}\"",
            "rm -rf \"\$app_dir\"",
            "mkdir -p \"\$app_dir/usr/lib\" \"$(dirname \"\$artifact\")\"",
            "cp -a \"\$app_image_dir\" \"\$app_dir/usr/lib/FuoEvolve\"",
            "find \"\$app_dir/usr/lib/FuoEvolve/lib/app/resources/desktop-python/venv/bin\" -maxdepth 1 -type f -exec chmod +x {} +",
            "python_bin=\"\$app_dir/usr/lib/FuoEvolve/lib/app/resources/desktop-python/venv/bin/python\"",
            "python_lib=\"$(ldd \"\$python_bin\" | awk '/libpython/ { print \$3; exit }')\"",
            "test -f \"\$python_lib\"",
            "cp -L \"\$python_lib\" \"\$app_dir/usr/lib/FuoEvolve/lib/app/resources/desktop-python/venv/lib/\"",
            "cat > \"\$app_dir/AppRun\" <<'EOF'",
            "#!/usr/bin/env bash",
            "set -euo pipefail",
            "export APPDIR=\"\${APPDIR:-\$(cd \"\$(dirname \"\${BASH_SOURCE[0]}\")\" && pwd)}\"",
            "export LD_LIBRARY_PATH=\"\$APPDIR/usr/lib/FuoEvolve/lib/app/resources/desktop-python/venv/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"",
            "exec \"\$APPDIR/usr/lib/FuoEvolve/bin/FuoEvolve\" \"\$@\"",
            "EOF",
            "chmod +x \"\$app_dir/AppRun\"",
            "cat > \"\$app_dir/fuo-evolve.desktop\" <<'EOF'",
            "[Desktop Entry]",
            "Type=Application",
            "Name=FuoEvolve",
            "Exec=FuoEvolve",
            "Icon=fuo-evolve",
            "Categories=AudioVideo;Audio;Player;",
            "Terminal=false",
            "EOF",
            "cp \"\$icon_file\" \"\$app_dir/fuo-evolve.png\"",
            "curl -L --retry 3 --retry-delay 5 -o \"\$appimagetool\" https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage",
            "chmod +x \"\$appimagetool\"",
            "APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 \"\$appimagetool\" \"\$app_dir\" \"\$artifact\"",
            "test -f \"\$artifact\"",
            "chmod +x \"\$artifact\"",
        ).joinToString("\n"),
    )
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        dependsOn("prepareDesktopPython")
        systemProperty("fuo.desktop.python", desktopPythonDir.file("bin/python").asFile.absolutePath)
        systemProperty("fuo.desktop.bridgeScript", desktopBridgeScript.asFile.absolutePath)
        systemProperty("fuo.desktop.androidPythonDir", androidPythonDir.asFile.absolutePath)
    }
}

tasks.matching { task ->
    task.name == "prepareAppResources" ||
        task.name == "runDistributable" ||
        task.name.startsWith("create") ||
        task.name.startsWith("package")
}.configureEach {
    dependsOn(syncDesktopAppResources)
}
