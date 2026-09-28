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
    maven("https://api.modrinth.com/maven")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

sourceSets {
    main {
        java {
            srcDir("../paper/src/main/java")
            exclude(
                "io/github/kardane/jarvisminecraft/paper/JarvisPaperPlugin.java"
            )
        }
    }
}

dependencies {
    implementation(project(":minecraft:common"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.49-alpha")
    compileOnly("net.coreprotect:coreprotect:24.0")
    compileOnly("maven.modrinth:DKY9btbd:btHBavWa")
    compileOnly("maven.modrinth:1u6JkXh5:LuxQFdjH")
    compileOnly("com.github.Zrips:CMI-API:9.8.6.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

val commonEmbeddedJar = project(":minecraft:common")
    .tasks
    .named<Jar>("shadowJar")

tasks.jar {
    dependsOn(commonEmbeddedJar)
    from(commonEmbeddedJar.map { zipTree(it.archiveFile.get().asFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("jarvisminecraft-paper-26.3.jar")
}
