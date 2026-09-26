import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.playpro.com/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
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
    testImplementation(libs.paper.api)
    compileOnly("net.coreprotect:coreprotect:24.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.14")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.16")
    compileOnly("com.github.Zrips:CMI-API:9.8.6.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

val commonEmbeddedJar = project(":minecraft:common")
    .tasks
    .named<Jar>("shadowJar")

tasks.jar {
    dependsOn(commonEmbeddedJar)
    from(commonEmbeddedJar.map { zipTree(it.archiveFile.get().asFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("jarvisminecraft-paper.jar")
}

val t06Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T06 Paper Adapter contract tests without a live Paper server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.T06VerificationMain")
}

val t11Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T11 CoreProtect history Provider contract tests without a live Paper server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.integrations.coreprotect.T11VerificationMain")
}

val t12Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T12 WorldGuard Provider contract tests without a live Paper server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.integrations.worldguard.T12VerificationMain")
}

val t13Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T13 Paper optional Provider registry combination tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.integrations.T13VerificationMain")
}

val t14Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T14 CMI profile Tool contract tests without a live CMI server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.paper.integrations.cmi.T14VerificationMain")
}

tasks.named("check") {
    dependsOn(t06Verification, t11Verification, t12Verification, t13Verification, t14Verification)
}
