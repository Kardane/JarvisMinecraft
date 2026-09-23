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
