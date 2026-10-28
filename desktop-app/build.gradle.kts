plugins { application }

dependencies {
    implementation(project(":game-core"))
    implementation(project(":content-tools"))
    implementation(project(":network"))
    implementation(libs.jme.core)
    implementation(libs.jme.desktop)
    implementation(libs.jme.lwjgl3)
    implementation(libs.jme.plugins)
    runtimeOnly(libs.jme.jogg)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
application { mainClass.set("sk.atomicplumberman.desktop.DesktopLauncher") }
sourceSets.main { resources.srcDir(rootProject.file("assets/runtime")) }

tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Open the real window, play a scripted round with audio, then exit. Requires a display."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--smoke-test")
}

// Portable app image with its own Java runtime. jpackage only targets the OS it runs on.
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a self-contained app image with a bundled Java 21 runtime in build/jpackage."
    dependsOn(tasks.named("installDist"))
    val install = layout.buildDirectory.dir("install/desktop-app/lib")
    val output = layout.buildDirectory.dir("jpackage")
    val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val version = project.version.toString()
    val mainClass = application.mainClass
    // From `jdeps --print-module-deps` over lib/; jdk.unsupported for LWJGL's reflective Unsafe access.
    val modules = "java.base,java.compiler,java.desktop,java.management,java.prefs,java.sql,jdk.unsupported"
    inputs.dir(install)
    outputs.dir(output)
    doFirst {
        delete(output)
        val windows = System.getProperty("os.name").startsWith("Windows")
        executable = jdk.get().metadata.installationPath.file(if (windows) "bin/jpackage.exe" else "bin/jpackage").asFile.path
        args("--type", "app-image", "--name", "AtomicPlumberman",
            "--app-version", version.substringBefore("-"),
            "--input", install.get().asFile.path, "--main-jar", "desktop-app-$version.jar",
            "--main-class", mainClass.get(), "--dest", output.get().asFile.path,
            "--add-modules", modules, "--jlink-options", "--strip-debug --no-man-pages --no-header-files")
    }
}

// The archive root carries what a player opens first; GPL: every binary carries its licence.
val windows = System.getProperty("os.name").startsWith("Windows")
val packageName = "AtomicPlumberman-${project.version}-${if (windows) "windows" else "linux"}-x64"
val playerDocs = listOf("LICENSE", "docs/CREDITS.md", "docs/PLAYER.md", "docs/RELEASE_NOTES.md").map { rootProject.file(it) }
fun AbstractArchiveTask.portable() {
    group = "distribution"
    description = "Pack the app image with licence and player docs as build/distributions/$packageName."
    from(jpackageImage)
    from(playerDocs) { into("AtomicPlumberman") }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
if (windows) {
    tasks.register<Zip>("portableArchive") {
        portable()
        archiveFileName.set("$packageName.zip")
    }
} else {
    tasks.register<Tar>("portableArchive") {
        portable()
        // Reproducible archives normalise modes to 644; keep launchers and runtime tools executable.
        eachFile { if (file.canExecute()) permissions { unix("rwxr-xr-x") } }
        compression = Compression.GZIP
        archiveFileName.set("$packageName.tar.gz")
    }
}
