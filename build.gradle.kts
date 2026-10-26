// luau-jni: the Java library (luau-jni-<version>.jar) and this platform's native library in a jar of its own
// (luau-jni-natives-<platform>-<version>.jar), built with CMake (native/). Releases are built by GitHub Actions
// (.github/workflows/release.yml) and published as a GitHub release's files, for Gradle's ivy repositories (README).
//   ./gradlew build              the jars, into build/libs, and the tests
//   ./gradlew publishToMavenLocal   the same, into ~/.m2
// CMake: -Pcmake=, else CMAKE, else on the PATH, else Visual Studio's own (Windows).

plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.forka42"
version = providers.gradleProperty("version").get()

repositories { mavenCentral() }

java {
    withSourcesJar()
}
// (Java 17's bytecode: Android's, and any desktop Java since.)
tasks.withType<JavaCompile>().configureEach { options.release = 17; options.encoding = "UTF-8" }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val os = System.getProperty("os.name").lowercase().let { if (it.contains("win")) "windows" else if (it.contains("mac")) "macos" else "linux" }
val arch = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else if (it == "aarch64" || it == "arm64") "arm64" else it }
val platform = "$os-$arch"
val libraryName = when (os) { "windows" -> "luaujni.dll"; "macos" -> "libluaujni.dylib"; else -> "libluaujni.so" }
val cmakeDir = layout.buildDirectory.dir("cmake/$platform")
val library = cmakeDir.map { it.file("lib/$libraryName") }

fun cmake(): String {
    (findProperty("cmake") as String? ?: System.getenv("CMAKE"))?.let { return it }
    val exe = if (os == "windows") "cmake.exe" else "cmake"
    System.getenv("PATH").split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.isFile }?.let { return it.path }
    if (os == "windows") {
        val vswhere = File(System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)", "Microsoft Visual Studio/Installer/vswhere.exe")
        if (vswhere.isFile) {
            val vs = providers.exec { commandLine(vswhere.path, "-latest", "-products", "*", "-property", "installationPath") }.standardOutput.asText.get().trim()
            File(vs, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe").takeIf { it.isFile }?.let { return it.path }
        }
    }
    error("No CMake: put it on the PATH, or give -Pcmake=")
}

val cmakeBuild = tasks.register("cmakeBuild") {
    description = "Builds the native library (native/) for this platform with CMake."
    inputs.dir("native")
    outputs.file(library)
    doLast {
        val dir = cmakeDir.get().asFile
        val javaHome = System.getProperty("java.home")
        providers.exec { commandLine(cmake(), "-S", file("native").path, "-B", dir.path, "-DCMAKE_BUILD_TYPE=Release", "-DJAVA_HOME=$javaHome") }.result.get()
        providers.exec { commandLine(cmake(), "--build", dir.path, "--config", "Release", "--target", "luaujni", "--parallel") }.result.get()
    }
}

val nativesJar = tasks.register<Jar>("nativesJar") {
    description = "This platform's native library, as a jar (luau-jni-natives-<platform>)."
    archiveBaseName = "luau-jni-natives-$platform"
    from(library) { into("io/github/forka42/luaujni/natives/$platform") }
    dependsOn(cmakeBuild)
}
tasks.assemble { dependsOn(nativesJar) }

tasks.test {
    useJUnitPlatform()
    dependsOn(cmakeBuild)
    systemProperty("luaujni.library.path", library.get().asFile.path)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The benchmarks (src/bench: Bench), on this platform's library: ./gradlew bench
val bench = sourceSets.create("bench") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
tasks.register<JavaExec>("bench") {
    description = "Times crossing between Luau and Java, and Luau's own code."
    dependsOn(cmakeBuild)
    classpath = bench.runtimeClasspath
    mainClass = "io.github.forka42.luaujni.Bench"
    systemProperty("luaujni.library.path", library.get().asFile.path)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The same, as a GraalVM native image (-O3; Native Image runs JNI differently from the JVM): ./gradlew benchNative
// (-PgraalvmHome=, else GRAALVM_HOME, else the newest under ~/.graalvm-oracle or ~/.graalvm).
val graalvmHome: File? = ((findProperty("graalvmHome") as String?) ?: System.getenv("GRAALVM_HOME"))?.let { file(it) }
    ?: listOf(".graalvm-oracle", ".graalvm").firstNotNullOfOrNull { d ->
        File(System.getProperty("user.home"), d).listFiles()?.filter { it.resolve("bin").isDirectory }?.maxByOrNull { it.name } }
tasks.register<Exec>("benchNative") {
    description = "Times the benchmarks as a GraalVM native image."
    dependsOn(cmakeBuild, tasks.named("benchClasses"), tasks.classes)
    val out = layout.buildDirectory.dir("bench-native").get().asFile
    doFirst {
        val home = graalvmHome ?: error("No GraalVM: give -PgraalvmHome=, or set GRAALVM_HOME")
        out.mkdirs()
        val ni = home.resolve("bin/native-image" + if (os == "windows") ".cmd" else "").path
        val cp = (bench.runtimeClasspath.files + sourceSets.main.get().output.files).filter { it.exists() }.joinToString(File.pathSeparator)
        providers.exec { commandLine(ni, "-O3", "--no-fallback", "--enable-native-access=ALL-UNNAMED", "-cp", cp, "-o", out.resolve("bench").path, "io.github.forka42.luaujni.Bench") }.result.get()
    }
    commandLine(out.resolve("bench").path + if (os == "windows") ".exe" else "", "-Dluaujni.library.path=" + library.get().asFile.path)
}

// The licence and Luau's notice in each jar.
tasks.withType<Jar>().configureEach { into("META-INF") { from("LICENSE", "THIRD-PARTY-NOTICES.md") } }

publishing {
    publications {
        create<MavenPublication>("main") {
            artifactId = "luau-jni"
            from(components["java"])
        }
        create<MavenPublication>("natives") {
            artifactId = "luau-jni-natives-$platform"
            artifact(nativesJar)
        }
    }
}
