plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("kapt") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "de.frinshy"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.kord:kord-core:0.17.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("org.reflections:reflections:0.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation(kotlin("test"))
}

// Help IntelliJ understand annotation processing
kapt {
    correctErrorTypes = true
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("de.frinshy.MainKt")
}