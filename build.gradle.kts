plugins {
    java
}

group = "vt"
// The workflow passes -Pappversion=<same number as the GitHub release>
version = (findProperty("appversion") ?: "dev").toString()

repositories {
    mavenCentral()
}

val fx = "21.0.5"

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    for (m in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$m:$fx:win")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filesMatching("vt/app.properties") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    archiveFileName.set("vt-launcher.jar")
}

// Copies the app jar + all dependency jars into build/dist (input for jpackage)
tasks.register<Copy>("dist") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath) {
        // drop classifier-less JavaFX stubs, keep the *-win jars
        exclude { it.name.matches(Regex("javafx-.*-[0-9.]+\\.jar")) }
    }
    into(layout.buildDirectory.dir("dist"))
}
