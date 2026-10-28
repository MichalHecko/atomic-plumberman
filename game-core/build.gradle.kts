plugins { `java-library` }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.register("verifyCoreIsolation") {
    description = "Keep the simulation free of runtime libraries, including JME and JSON."
    doLast {
        check(configurations.runtimeClasspath.get().files.isEmpty()) {
            "game-core must have no external runtime dependencies"
        }
    }
}
tasks.check { dependsOn("verifyCoreIsolation") }
