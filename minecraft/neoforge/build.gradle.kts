import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("net.neoforged.moddev")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val prepareT08BootSmoke by tasks.registering {
    group = "verification"
    description = "Prepares an isolated NeoForge dedicated-server boot smoke directory."
    doLast {
        val runDir = file("run/t08-boot-smoke")
        runDir.mkdirs()
        runDir.resolve("eula.txt").writeText("eula=true\n")
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
            gameDirectory = file("run/t08-boot-smoke")
            programArgument("--nogui")
            systemProperty("jarvis.t08BootSmoke", "true")
            systemProperty("jarvis.sharedSecret", "correct-horse-battery-staple")
            taskBefore(prepareT08BootSmoke)
            disableIdeRun()
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

val commonMainOutput = project(":minecraft:common")
    .extensions
    .getByType<SourceSetContainer>()
    .named("main")
    .map { it.output }

tasks.jar {
    dependsOn(":minecraft:common:classes")
    from(commonMainOutput)
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
    dependsOn("runT08BootSmoke")
}
