rootProject.name = "craftradar-votes"
// core — логика без серверных зависимостей (Java 8 bytecode); bukkit — плагин (клей Bukkit/Votifier), собирает итоговый jar
include("core", "bukkit")
