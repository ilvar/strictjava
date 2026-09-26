plugins {
    application
    java
}

group = "pw.rkd"
version = "0.2.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "pw.rkd.strictjava.Main"
}

val analyzers by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    analyzers("com.google.errorprone:error_prone_core:2.50.0")
    analyzers("com.uber.nullaway:nullaway:0.14.2")
    analyzers("org.jspecify:jspecify:1.0.0")
}

tasks.register<Sync>("prepareAnalyzers") {
    from(analyzers)
    into(layout.buildDirectory.dir("analyzers"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
