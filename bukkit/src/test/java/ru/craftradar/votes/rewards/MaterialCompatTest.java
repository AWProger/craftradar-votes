package ru.craftradar.votes.rewards;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ItemGranter.usable/materialExists через рефлексию дают то же, что прямые isLegacy/isAir/isItem (API 1.20.4 в тестах). */
class MaterialCompatTest {
    @Test void sameAsDirectCalls() {
        for (Material m : Material.values()) {
            boolean direct = !m.isLegacy() && !m.isAir() && m.isItem();
            assertEquals(direct, ItemGranter.usable(m), m.name());
            boolean v100 = !m.isLegacy() && !m.isAir();   // CraftRadarVotesPlugin.materialExists в 1.0.0
            assertEquals(v100, ItemGranter.materialExists(m.name()), m.name());
        }
    }

    @Test void configNames() {
        assertTrue(ItemGranter.materialExists("DIAMOND"));
        assertFalse(ItemGranter.materialExists("AIR"));
        assertFalse(ItemGranter.materialExists("CAVE_AIR"));
        assertFalse(ItemGranter.materialExists("LEGACY_STONE"));
        assertFalse(ItemGranter.materialExists("diamond"));   // точное имя, как раньше Material.getMaterial
        assertFalse(ItemGranter.materialExists(null));
        assertFalse(ItemGranter.usable(null));
    }
}
