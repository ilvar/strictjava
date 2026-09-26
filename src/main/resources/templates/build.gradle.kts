import java.net.URI
import java.security.MessageDigest
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    application
    java
}

group = "app"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("app.Main")
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
}

val lockedConfigurations = setOf(
    "compileClasspath",
    "runtimeClasspath",
    "testCompileClasspath",
    "testRuntimeClasspath",
)

configurations.matching { it.name in lockedConfigurations }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val testApp = tasks.register<JavaExec>("testApp") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.MainTest")
    enableAssertions = true
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests.set(false)
    dependsOn(testApp)
}

val formatterVersion = "1.36.1"
val formatterSha256 = "25b400f003089d23cc5320cdaf1a16cabee19b8aa3434d0ff021b3d9f42154b4"
val formatterUrl =
    "https://github.com/google/google-java-format/releases/download/v$formatterVersion/google-java-format-$formatterVersion-all-deps.jar"
val formatterFile = layout.projectDirectory.file(".gradle/strictjava-tools/google-java-format-$formatterVersion-all-deps.jar")

fun sha256(path: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val prepareFormatter = tasks.register("prepareFormatter") {
    outputs.file(formatterFile)
    doLast {
        val target = formatterFile.asFile
        if (target.isFile && sha256(target) == formatterSha256) {
            return@doLast
        }

        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.delete()
        URI(formatterUrl).toURL().openStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256(temporary)
        require(actual == formatterSha256) {
            "google-java-format checksum mismatch: expected $formatterSha256, got $actual"
        }
        if (target.exists()) {
            target.delete()
        }
        require(temporary.renameTo(target)) {
            "failed to move formatter into place: $target"
        }
    }
}

fun javaSourceArguments(): List<String> =
    fileTree("src") {
        include("**/*.java")
    }.files.sortedBy { it.path }.map { it.absolutePath }

val java25 = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}

tasks.register<Exec>("formatCheck") {
    dependsOn(prepareFormatter)
    doFirst {
        commandLine(
            listOf(
                java25.get().executablePath.asFile.absolutePath,
                "-jar",
                formatterFile.asFile.absolutePath,
                "--dry-run",
                "--set-exit-if-changed",
            ) + javaSourceArguments()
        )
    }
}

tasks.register<Exec>("format") {
    dependsOn(prepareFormatter)
    doFirst {
        commandLine(
            listOf(
                java25.get().executablePath.asFile.absolutePath,
                "-jar",
                formatterFile.asFile.absolutePath,
                "--replace",
            ) + javaSourceArguments()
        )
    }
}

tasks.register("strictjavaClasspath") {
    doLast {
        println(configurations.getByName("compileClasspath").asPath)
    }
}

tasks.named("check") {
    dependsOn("formatCheck", testApp)
}
