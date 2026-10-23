import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.gradleup.shadow") version "8.3.5" }

// API, против которого компилируется клей. По умолчанию — самый старый из стабильных репозиториев (1.8.8),
// чтобы случайно не вызвать новый метод. Матрица компиляции: -PbukkitApi=<group:artifact:version>
val bukkitApi = providers.gradleProperty("bukkitApi").getOrElse("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")

dependencies {
    implementation(project(":core"))
    compileOnly(bukkitApi)
    compileOnly("com.github.NuVotifier.NuVotifier:nuvotifier-api:2.7.1")    // Vote (у classic Votifier 1.9 нет getAdditionalData — обёрнуто)
    compileOnly("com.github.NuVotifier.NuVotifier:nuvotifier-bukkit:2.7.1") { isTransitive = false } // VotifierEvent
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT") { exclude(group = "org.ow2.asm") }
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }

tasks.jar { archiveClassifier.set("thin") }   // без core/gson — не для установки
tasks.shadowJar {
    archiveBaseName.set("craftradar-votes")
    archiveClassifier.set("")
    relocate("com.google.gson", "ru.craftradar.votes.lib.gson")
    exclude("META-INF/versions/**", "module-info.class", "META-INF/maven/**")
    from(rootProject.file("third-party/gson-LICENSE.txt")) { into("META-INF") }
}

// Проверка собранного jar: bytecode <= Java 8, plugin.yml, gson только затенённый, нет секретов
val verifyJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    doLast {
        val f = jarFile.get().asFile
        val errs = mutableListOf<String>()
        ZipFile(f).use { z ->
            val names = z.entries().toList().map { it.name }
            for (e in z.entries()) {
                val bytes = z.getInputStream(e).readBytes()
                if (e.name.endsWith(".class")) {
                    val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                    if (major > 52) errs += "${e.name}: class major $major > 52 (Java 8)"
                    if (String(bytes, Charsets.ISO_8859_1).contains("com/google/gson/")) errs += "${e.name}: незатенённая ссылка на com.google.gson"
                    if (!e.name.startsWith("ru/craftradar/votes/")) errs += "${e.name}: класс вне ru.craftradar.votes"
                }
            }
            val yml = String(z.getInputStream(z.getEntry("plugin.yml")).readBytes(), Charsets.UTF_8)
            if (!yml.contains("main: ru.craftradar.votes.CraftRadarVotesPlugin")) errs += "plugin.yml: main"
            if (!yml.contains("version: '${project.version}'")) errs += "plugin.yml: version"
            if (!yml.contains("name: CraftRadarVotes")) errs += "plugin.yml: name"
            if (!Regex("(?m)^api-version: '1\\.13'$").containsMatchIn(yml)) errs += "plugin.yml: api-version должен быть '1.13' (1.13+ без legacy, старые серверы ключ игнорируют)"
            val cfg = String(z.getInputStream(z.getEntry("config.yml")).readBytes(), Charsets.UTF_8)
            if (!Regex("(?m)^secret: \"\"").containsMatchIn(cfg) || !Regex("(?m)^key_id: \"\"").containsMatchIn(cfg)) errs += "config.yml: key_id/secret должны быть пустыми"
            if (!Regex("(?m)^api_url: \"https://craftradar.ru\"").containsMatchIn(cfg)) errs += "config.yml: api_url по умолчанию"
            if (!Regex("(?m)^  enabled: false").containsMatchIn(cfg)) errs += "config.yml: rewards.enabled по умолчанию false"
            if (!names.contains("META-INF/gson-LICENSE.txt")) errs += "нет лицензии gson"
        }
        if (errs.isNotEmpty()) throw GradleException("verifyJar:\n" + errs.joinToString("\n"))
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val out = layout.buildDirectory.file("libs/SHA256SUMS").get().asFile
        out.writeText("$sha  ${f.name}\n")
        println("verifyJar OK: ${f.name} $sha")
    }
}
tasks.assemble { dependsOn(verifyJar) }

// Classpath API (для realserver/ApiLinkCheck.java): ./gradlew -q :bukkit:printApiClasspath -PbukkitApi=...
tasks.register("printApiClasspath") {
    // + gson: Vote из nuvotifier-api ссылается на com.google.gson, а API 1.6.4 gson не тянет (серверы с NuVotifier его дают)
    val cp = configurations.compileClasspath
    val rt = configurations.runtimeClasspath
    doLast {
        val files = cp.get().files.filter { !it.path.contains("/core/") } + rt.get().files.filter { it.name.startsWith("gson-") }
        println(files.distinct().joinToString(File.pathSeparator))
    }
}
