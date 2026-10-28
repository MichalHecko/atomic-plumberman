plugins { application; `java-library` }

dependencies {
    api(project(":game-core"))
    implementation(libs.gson)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
application { mainClass.set("sk.atomicplumberman.content.ContentValidator") }

tasks.register<JavaExec>("validateContent") {
    group = "verification"
    description = "Validate bundled arenas, themes and the asset manifest without OpenGL."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args(listOf("--assets", rootProject.projectDir.absolutePath)
        + rootProject.fileTree("assets/runtime/Data").matching { include("**/*.json") }.files.sorted().map { it.absolutePath })
}
tasks.check { dependsOn("validateContent") }

tasks.register<JavaExec>("soakTest") {
    group = "verification"
    description = "Plan 12.3/12.5: 1000 random rounds over every arena and mode, then the load scene; -Prounds=N."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("sk.atomicplumberman.content.Soak")
    args(rootProject.file("assets/runtime/Data/arenas").absolutePath, (findProperty("rounds") ?: "1000").toString(),
        layout.buildDirectory.dir("soak").get().asFile.absolutePath)
}
