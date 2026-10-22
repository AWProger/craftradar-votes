// Ядро: голоса, очередь, HMAC-транспорт, движок наград. Никаких org.bukkit / Votifier в main.
dependencies {
    implementation("com.google.code.gson:gson:2.10.1") // в итоговом jar затеняется (relocate) модулем bukkit
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT") { exclude(group = "org.ow2.asm") } // только Material-имена в CabinetFixturesTest
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.yaml:snakeyaml:2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
