plugins {
    alias(libs.plugins.neoforge.moddev)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

neoForge {
    version = libs.versions.neoforge.get()
}

dependencies {
    implementation(project(":minecraft:common"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}
