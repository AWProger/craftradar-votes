// Версия плагина и общие правила; модули: core/, bukkit/. Итоговый jar: bukkit/build/libs/craftradar-votes-<ver>.jar
allprojects {
    group = "ru.craftradar"
    version = "1.2.0"
}

subprojects {
    apply(plugin = "java")
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://jitpack.io")
        maven("https://repo.codemc.org/repository/maven-public/")   // SkinsRestorer API (модуль skins)
    }
    // JDK сборки: 21; для compile-матрицы против API на Java 25 (Paper 26.x): -PcompileJdk=25
    val compileJdk = providers.gradleProperty("compileJdk").getOrElse("21").toInt()
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(compileJdk)) }
        disableAutoTargetJvm()   // API серверов объявляют JVM 17/21/25 в metadata; наш bytecode — 8 (проверяет verifyJar)
    }
    // main — Java 8 bytecode и только Java 8 API (--release проверяет и API); тесты — Java 21
    tasks.named<JavaCompile>("compileJava") { options.release.set(8); options.compilerArgs.add("-Xlint:-options") }
    tasks.named<JavaCompile>("compileTestJava") { options.release.set(21) }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
