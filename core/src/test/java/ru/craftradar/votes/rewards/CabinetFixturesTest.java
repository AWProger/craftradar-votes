package ru.craftradar.votes.rewards;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Прогоняет YAML кабинета (cabinet/) и фикстуры rewards/valid|invalid через настоящий ConfigValidator. Первая строка файла: «# expect: valid» или «# expect: <путь ошибки>». */
class CabinetFixturesTest {
    private static final Path RES = Path.of("src/test/resources");

    private static boolean material(String n) {
        Material m = Material.getMaterial(n);
        return m != null && !m.isLegacy() && !m.isAir();
    }

    @SuppressWarnings("unchecked")
    private static ValidationResult validate(Path f) throws Exception {
        String text = Files.readString(f);
        Object root = new Yaml().load(text);
        assertInstanceOf(Map.class, root, f + ": корень не map");
        Object rewards = ((Map<String, Object>) root).get("rewards");
        assertInstanceOf(Map.class, rewards, f + ": нет корня rewards");
        return ConfigValidator.validate((Map<String, Object>) rewards, CabinetFixturesTest::material);
    }

    private static String expect(Path f) throws Exception {
        String first = Files.readAllLines(f).get(0);
        assertTrue(first.startsWith("# expect: "), f + ": нет заголовка expect");
        return first.substring("# expect: ".length()).trim();
    }

    private static List<Path> yml(String dir) throws Exception {
        try (Stream<Path> s = Files.list(RES.resolve(dir))) { return s.filter(p -> p.toString().endsWith(".yml")).sorted().toList(); }
    }

    @Test void кабинет_генерирует_только_валидные_конфиги() throws Exception {
        List<Path> files = yml("cabinet");
        assertTrue(files.size() >= 7, "сценариев кабинета: " + files.size());
        for (Path f : files) {
            assertEquals("valid", expect(f), f.toString());
            ValidationResult r = validate(f);
            assertTrue(r.ok(), f + ": " + r.errors());
        }
    }

    @Test void валидные_фикстуры_проходят() throws Exception {
        for (Path f : yml("rewards/valid")) {
            ValidationResult r = validate(f);
            assertTrue(r.ok(), f + ": " + r.errors());
        }
    }

    @Test void невалидные_фикстуры_дают_ошибку_с_ожидаемым_путём() throws Exception {
        List<Path> files = yml("rewards/invalid");
        assertFalse(files.isEmpty());
        for (Path f : files) {
            String path = expect(f);
            ValidationResult r = validate(f);
            assertFalse(r.ok(), f + ": принят, а должен быть отклонён");
            assertTrue(r.errors().stream().anyMatch(e -> e.startsWith(path + ":") || e.startsWith(path + "[") || e.startsWith(path + ".")),
                    f + ": ожидался путь " + path + ", ошибки: " + r.errors());
        }
    }
}
