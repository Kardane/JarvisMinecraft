import org.gradle.api.file.DuplicatesStrategy
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val embeddedRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Minecraft 1.21.8 distributions already provide Gson. Keep it compile-only
    // so the platform artifact does not introduce a second Gson copy.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")

    // Compile and test against the official OpenAI Java SDK.
    compileOnly("com.openai:openai-java:4.69.2")
    testImplementation("com.openai:openai-java:4.69.2")

    // E16: package the SDK and its runtime dependencies into the embedded common
    // payload. Shadow relocates these packages away from the Minecraft classpath.
    embeddedRuntime("com.openai:openai-java:4.69.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

val relocatedShadowJar = tasks.named<ShadowJar>("shadowJar")

relocatedShadowJar.configure {
    archiveClassifier.set("embedded")
    configurations = listOf(embeddedRuntime)

    // Service descriptors need to be merged before duplicate resources are
    // discarded. All embedded dependency packages are relocated below.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")

    // Kotlin reflection depends on canonical Kotlin packages and built-ins metadata.
    // Keep Kotlin in its original package; relocate the remaining SDK dependencies.
    relocate("kotlin", "kotlin") {
        skipStringConstants = true
    }
    // OpenAI models rely on Kotlin metadata (@kotlin.Metadata) for Jackson reflection.
    // Relocating com.openai breaks Kotlin reflection due to unresolved class names.
    // Keep com.openai in its original package; isolate the third-party dependencies (Jackson, OkHttp, Okio).
    relocate("com.openai", "com.openai") {
        skipStringConstants = true
    }


    enableAutoRelocation = true
    relocationPrefix = "io.github.kardane.jarvisminecraft.internal.shaded"
}

tasks.named("assemble") {
    dependsOn(relocatedShadowJar)
}

val embeddedBrainVerification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Embedded Brain E8-E10 orchestration, audit and gateway contract tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.common.EmbeddedBrainVerificationMain")
}

val embeddedBrainParityVerification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs E12 Remote/Embedded policy parity fixtures and safety invariants."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.common.EmbeddedBrainParityVerificationMain")
    systemProperty("jarvis.repoRoot", rootProject.projectDir.absolutePath)
}

val jarvisConfigVerification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Phase 1 runtime configuration validation and fail-safe reload checks."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set(
        "io.github.kardane.jarvisminecraft.common.config.JarvisConfigVerificationMain"
    )
}

tasks.named("check") {
    dependsOn(embeddedBrainVerification)
    dependsOn(embeddedBrainParityVerification)
    dependsOn(jarvisConfigVerification)
}

val embeddedBrainLiveVerification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Embedded Brain E11 Live Jev/Luna integration verification against live APIs."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.common.EmbeddedBrainLiveVerificationMain")
    systemProperty("jarvis.repoRoot", rootProject.projectDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}
