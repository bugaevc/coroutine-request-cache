import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.20"
    id("org.jetbrains.dokka") version "1.5.0"
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "io.github.bugaevc"
version = "0.1"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.0")
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        )
    }
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource) {
        rename { "io/github/bugaevc/requestcache/$it" }
    }
}

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    archiveClassifier.set("javadoc")
    from(tasks["dokkaHtml"])
    dependsOn(tasks["dokkaHtml"])
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(dokkaJar)
        }
    }
}