plugins { application }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    // Everything Wayland comes from the vendored library subproject.
    implementation(project(":vendor:wayland-lite"))
}

application {
    mainClass.set("wcm.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The isolation tool used to pin the poll()/upcall bugs down.
tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("wcm.Probe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args = ((findProperty("probeArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}

// Render the test pattern for each mode straight into docs/renders/.
// Runnable from the root by name: ./gradlew render-hdr10
val renderModes = listOf("hdr10", "scrgb", "p3", "none")
val renderTasks = renderModes.map { mode ->
    tasks.register<JavaExec>("render-$mode") {
        group = "documentation"
        description = "Render the $mode test pattern into docs/renders/"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("wcm.Main")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(
            "--mode", mode, "--10bit", "--frames", "2",
            "--dump", "${rootProject.rootDir}/docs/renders/render-$mode.png",
        )
    }
}

tasks.register("renderAll") {
    group = "documentation"
    description = "Render every mode"
    dependsOn(renderTasks)
}
