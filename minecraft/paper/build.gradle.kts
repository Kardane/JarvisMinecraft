import org.gradle.api.tasks.SourceSetContainer

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
    implementation(project(":minecraft:common"))
    compileOnly(libs.paper.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

val commonMainOutput = project(":minecraft:common")
    .extensions
    .getByType<SourceSetContainer>()
    .named("main")
    .map { it.output }

tasks.jar {
    dependsOn(":minecraft:common:classes")
    from(commonMainOutput)
}

val t06Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T06 Paper Adapter contract tests without a live Paper server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.T06VerificationMain")
}

tasks.named("check") {
    dependsOn(t06Verification)
}
