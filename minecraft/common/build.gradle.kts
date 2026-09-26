plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

dependencies {
    // Minecraft 1.21.8 distributions already provide Gson. Keep it compile-only
    // in production and explicit on the common verification runtime.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")

    // E7: compile the embedded Luna bridge against the official OpenAI Java SDK.
    // Runtime bundling/shading is intentionally deferred to the packaging phase.
    compileOnly("com.openai:openai-java:4.69.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

val t03Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T03 fixture, reconnect, deduplication and scheduler contract tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.common.T03VerificationMain")
    systemProperty("jarvis.repoRoot", rootProject.projectDir.absolutePath)
}

tasks.named("check") {
    dependsOn(t03Verification)
}
