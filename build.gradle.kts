plugins { base }

allprojects {
    group = "sk.atomicplumberman"
    version = "1.0.0-rc.3"
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
        testLogging { events("failed", "skipped") }
    }
    tasks.withType<Jar>().configureEach {
        manifest { attributes("Implementation-Version" to project.version) }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
    tasks.register("resolveDependencies") {
        description = "Resolve module configurations (use --write-locks to update locks)."
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
}

// GPL: the corresponding source goes out with every binary release.
tasks.register<Zip>("sourceArchive") {
    group = "distribution"
    description = "Pack the versioned sources as build/distributions/AtomicPlumberman-<version>-source.zip."
    from(layout.projectDirectory) {
        // Screenshots of the original game are research material, not ours to redistribute.
        exclude("**/build/**", ".gradle/**", ".idea/**", "**/*.iml", "**/.~lock*", "docs/references/**")
        into("AtomicPlumberman-$version")
    }
    archiveFileName.set("AtomicPlumberman-$version-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }
