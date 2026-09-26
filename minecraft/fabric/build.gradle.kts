import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.fabric.loom)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

dependencies {
    add("minecraft", "com.mojang:minecraft:${libs.versions.minecraft.get()}")
    add("mappings", "net.fabricmc:yarn:${libs.versions.yarn.get()}:v2")
    add("modImplementation", libs.fabric.loader)
    add("modImplementation", libs.fabric.api)
    implementation(project(":minecraft:common"))
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
    archiveFileName.set("jarvisminecraft-fabric-dev.jar")
}

tasks.named<AbstractArchiveTask>("remapJar") {
    archiveFileName.set("jarvisminecraft-fabric.jar")
}

val verifyNoClientImports by tasks.registering {
    group = "verification"
    description = "Rejects client-only Minecraft/Fabric API imports in the dedicated-server Adapter."
    inputs.files(fileTree("src/main/java") { include("**/*.java") })
    doLast {
        val violations = inputs.files.files
            .filter { it.isFile }
            .filter { file ->
                val text = file.readText()
                text.contains("net.minecraft.client.") ||
                    text.contains("net.fabricmc.fabric.api.client.")
            }
        check(violations.isEmpty()) {
            "Client-only API imports found: " + violations.joinToString { it.relativeTo(projectDir).path }
        }
    }
}

val t07Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T07 Fabric Adapter contract tests without a live Fabric server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.fabric.T07VerificationMain")
}

tasks.named("check") {
    dependsOn(verifyNoClientImports)
    dependsOn(t07Verification)
}
