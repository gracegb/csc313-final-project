plugins {
    application
}

repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.3"

val lwjglNatives = run {
    val name = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        name.contains("win") -> "natives-windows"
        name.contains("linux") -> if (arch.contains("arm") || arch.contains("aarch64")) "natives-linux-arm64" else "natives-linux"
        name.contains("mac") -> if (arch.contains("arm") || arch.contains("aarch64")) "natives-macos-arm64" else "natives-macos"
        else -> throw IllegalStateException("Unsupported OS")
    }
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-stb")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")
}

application {
    mainClass.set("FightingGameLWJGL")
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
