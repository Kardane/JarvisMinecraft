import java.util.zip.ZipFile

plugins {
    base
}

group = "io.github.kardane.jarvisminecraft"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }
}

fun verifyE16Artifact(
    platform: String,
    artifact: File,
    entrypoint: String
) {
    check(artifact.isFile) {
        "E16 $platform artifact is missing: $artifact"
    }

    ZipFile(artifact).use { zip ->
        val names = zip.entries().asSequence()
            .map { it.name }
            .toSet()

        val required = listOf(
            entrypoint,
            "io/github/kardane/jarvisminecraft/common/brain/EmbeddedBrain.class",
            "io/github/kardane/jarvisminecraft/common/brain/ai/JdkJevClassifier.class",
            "io/github/kardane/jarvisminecraft/common/brain/ai/OpenAiLunaClient.class",
            "io/github/kardane/jarvisminecraft/common/audit/AsyncJsonlAuditSink.class",
            "io/github/kardane/jarvisminecraft/common/runtime/CommonRuntime.class",
            "io/github/kardane/jarvisminecraft/common/brain/PackagingSmoke.class"
        )
        check(required.all(names::contains)) {
            "E16 $platform artifact is missing required Embedded Brain classes."
        }

        val shadedPrefix =
            "io/github/kardane/jarvisminecraft/internal/shaded/"
        val requiredKotlinBuiltins = listOf(
            "kotlin/annotation/annotation.kotlin_builtins",
            "kotlin/collections/collections.kotlin_builtins",
            "kotlin/coroutines/coroutines.kotlin_builtins",
            "kotlin/internal/internal.kotlin_builtins",
            "kotlin/kotlin.kotlin_builtins",
            "kotlin/ranges/ranges.kotlin_builtins",
            "kotlin/reflect/reflect.kotlin_builtins"
        )
        check(requiredKotlinBuiltins.all(names::contains)) {
            "E16 $platform artifact is missing Kotlin reflection built-ins resources."
        }
        check(listOf(
            "kotlin/Metadata.class",
            "kotlin/jvm/internal/Intrinsics.class",
            "kotlin/reflect/jvm/internal/KClassImpl.class"
        ).all(names::contains)) {
            "E16 $platform artifact is missing the canonical Kotlin runtime classes."
        }

        check(
            names.any {
                it.startsWith("com/openai/")
            }
        ) {
            "E16 $platform artifact does not contain canonical OpenAI SDK classes."
        }
        check(
            names.none {
                it.startsWith(shadedPrefix + "com/openai/")
            }
        ) {
            "E16 $platform artifact unexpectedly relocated OpenAI SDK classes."
        }
        check(
            names.any {
                it.startsWith(shadedPrefix + "com/fasterxml/jackson/")
            }
        ) {
            "E16 $platform artifact does not contain relocated Jackson classes."
        }
        check(
            names.any {
                it.startsWith(shadedPrefix + "okhttp3/")
            }
        ) {
            "E16 $platform artifact does not contain relocated OkHttp classes."
        }

        val forbiddenPrefixes = listOf(
            "com/fasterxml/jackson/",
            "okhttp3/",
            "okio/",
            "com/google/gson/"
        )
        val unrelocatedSdkEntries = names
            .map { it.replace(Regex("^META-INF/versions/[^/]+/"), "") }
        check(unrelocatedSdkEntries.none { name ->
            forbiddenPrefixes.any(name::startsWith)
        }) {
            "E16 $platform artifact contains unrelocated SDK/Gson classes."
        }

        val builtinsProtocolName =
            "kotlin/reflect/jvm/internal/impl/serialization/deserialization/builtins/BuiltInSerializerProtocol.class"
        val builtinsProtocol = zip.getEntry(builtinsProtocolName)
        check(builtinsProtocol != null) {
            "E16 $platform artifact is missing the relocated Kotlin built-ins loader."
        }
        val builtinsProtocolBytes = zip.getInputStream(builtinsProtocol).use {
            String(it.readBytes(), Charsets.ISO_8859_1)
        }
        check(".kotlin_builtins" in builtinsProtocolBytes) {
            "E16 $platform artifact's Kotlin built-ins resource suffix is unexpected."
        }
        check(names.none { name ->
            name.startsWith(shadedPrefix + "kotlin/") && name.endsWith(".class")
        }) {
            "E16 $platform artifact unexpectedly relocated Kotlin runtime classes."
        }

        check(
            names.none { name ->
                name.endsWith("package.json")
                    || name.startsWith("node_modules/")
                    || name.endsWith(".js")
                    || name.endsWith(".mjs")
            }
        ) {
            "E16 $platform artifact unexpectedly contains Node/JavaScript runtime assets."
        }
    }
}

val verifyE16Artifacts by tasks.registering {
    group = "verification"
    description =
        "Verifies all deployable platform artifacts contain the relocated Embedded Brain runtime."

    dependsOn(
        ":minecraft:paper:jar",
        ":minecraft:fabric:remapJar",
        ":minecraft:neoforge:jar"
    )

    doLast {
        verifyE16Artifact(
            "Paper",
            project(":minecraft:paper")
                .layout.buildDirectory
                .file("libs/jarvisminecraft-paper.jar")
                .get().asFile,
            "io/github/kardane/jarvisminecraft/paper/JarvisPaperPlugin.class"
        )
        verifyE16Artifact(
            "Fabric",
            project(":minecraft:fabric")
                .layout.buildDirectory
                .file("libs/jarvisminecraft-fabric.jar")
                .get().asFile,
            "io/github/kardane/jarvisminecraft/fabric/JarvisFabricMod.class"
        )
        verifyE16Artifact(
            "NeoForge",
            project(":minecraft:neoforge")
                .layout.buildDirectory
                .file("libs/jarvisminecraft-neoforge.jar")
                .get().asFile,
            "io/github/kardane/jarvisminecraft/neoforge/JarvisNeoForgeMod.class"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyE16Artifacts)
}
