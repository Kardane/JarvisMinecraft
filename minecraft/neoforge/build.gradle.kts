import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    id("net.neoforged.moddev")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val t08BootRunDir = file("run/t08-boot-smoke")
val t08BootMarker = t08BootRunDir.resolve("boot-ok.marker")

val prepareT08BootSmoke by tasks.registering {
    group = "verification"
    description = "Prepares an isolated NeoForge dedicated-server boot smoke directory."
    dependsOn(tasks.named("jar"))
    doLast {
        t08BootRunDir.mkdirs()
        t08BootRunDir.resolve("eula.txt").writeText("eula=true\n")
        t08BootMarker.delete()

        val modsDir = t08BootRunDir.resolve("mods")
        modsDir.mkdirs()
        modsDir.listFiles()
            ?.filter { it.name.startsWith("neoforge-") || it.name.startsWith("jarvis") || it.name.contains("JARVIS", ignoreCase = true) }
            ?.forEach { it.delete() }

        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        jarFile.copyTo(modsDir.resolve("jarvisminecraft-t08-smoke.jar"), overwrite = true)
    }
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("jarvisminecraft") {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        create("t08BootSmoke") {
            server()
            loadedMods.set(emptySet())
            gameDirectory = t08BootRunDir
            programArgument("--nogui")
            systemProperty("jarvis.t08BootSmoke", "true")
            systemProperty("jarvis.t08BootMarker", t08BootMarker.absolutePath)
            systemProperty("jarvis.openaiApiKey", "t08-boot-smoke-openai")
            systemProperty("jarvis.typesafeApiKey", "t08-boot-smoke-typesafe")
            taskBefore(prepareT08BootSmoke)
            disableIdeRun()
        }
    }
}

val verifyT08BootSmoke by tasks.registering {
    group = "verification"
    description = "Boots the packaged JAR on NeoForge dedicated server and verifies the entrypoint marker."
    dependsOn("runT08BootSmoke")
    doLast {
        check(t08BootMarker.isFile) {
            "NeoForge dedicated-server smoke did not reach the JARVIS SERVER_STARTED entrypoint."
        }
        check(t08BootMarker.readText().trim() == "T08 dedicated server boot smoke OK") {
            "NeoForge dedicated-server smoke marker was invalid."
        }
    }
}

dependencies {
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
    archiveFileName.set("jarvisminecraft-neoforge.jar")
}

val verifyNoClientImports by tasks.registering {
    group = "verification"
    description = "Rejects client-only Minecraft/NeoForge API imports in the dedicated-server Adapter."
    inputs.files(fileTree("src/main/java") { include("**/*.java") })
    doLast {
        val violations = inputs.files.files
            .filter { it.isFile }
            .filter { file ->
                val text = file.readText()
                text.contains("net.minecraft.client.") ||
                    text.contains("net.neoforged.neoforge.client.")
            }
        check(violations.isEmpty()) {
            "Client-only API imports found: " + violations.joinToString { it.relativeTo(projectDir).path }
        }
    }
}

val t08Verification by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs T08 NeoForge Adapter contract tests without a live NeoForge server."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kardane.jarvisminecraft.neoforge.T08VerificationMain")
}

tasks.named("check") {
    dependsOn(verifyNoClientImports)
    dependsOn(t08Verification)
}
