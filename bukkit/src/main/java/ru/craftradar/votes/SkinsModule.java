package ru.craftradar.votes;

import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinVariant;
import net.skinsrestorer.api.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Клей модуля skins к SkinsRestorer 15.x (официальное API: SkinsRestorerProvider). Класс загружается, ТОЛЬКО если SkinsRestorer включён:
 * без него ссылки на net.skinsrestorer не резолвятся. Скин ставится так же, как «/skin url <адрес> [slim]» в самом SR:
 * текстуру подписывает MineSkin через SR (его ключ и очередь), результат хранится в SR по URL (наш URL содержит sha256 — повторный вход MineSkin не дёргает).
 */
final class SkinsModule {
    private final Plugin plugin;
    private final Logger log;
    private final SkinLookup lookup;
    private final SkinsRestorer sr;

    /** @throws IllegalStateException API SkinsRestorer не поднят (например, proxy mode без базы на бэкенде). */
    SkinsModule(Plugin plugin, SkinLookup lookup) {
        this.plugin = plugin; this.log = plugin.getLogger(); this.lookup = lookup;
        this.sr = SkinsRestorerProvider.get();
    }

    void onJoin(Player p) {
        final String name = p.getName();
        if (!SkinLookup.NICK.matcher(name).matches()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { apply(p, name); }
            catch (Throwable t) { log.log(Level.WARNING, "Скины: " + name + ": " + t); }   // MineSkin/SR: игрок остаётся со скином по умолчанию
        });
    }

    private void apply(Player p, String name) throws Exception {
        UUID id = p.getUniqueId();
        SkinLookup.Result r = lookup.lookup(name);
        if (r.status == SkinLookup.Status.ERROR) { log.fine("Скины: " + name + ": CraftRadar недоступен, пропуск"); return; }
        PlayerStorage ps = sr.getPlayerStorage();
        Optional<SkinIdentifier> cur = ps.getSkinIdOfPlayer(id);
        if (!lookup.mayReplace(cur.map(c -> c.getSkinType().name()).orElse(null), cur.map(SkinIdentifier::getIdentifier).orElse(null))) {
            log.fine("Скины: " + name + ": скин выбран в SkinsRestorer вручную, не трогаем");
            return;
        }
        if (r.status == SkinLookup.Status.NONE) {
            if (!cur.isPresent()) return;
            ps.removeSkinIdOfPlayer(id);   // на сайте скин сняли или ник отвязали: возвращаем то, что SR даёт по умолчанию
            reapply(p);
            log.fine("Скины: " + name + ": скин CraftRadar снят");
            return;
        }
        SkinVariant v = r.slim ? SkinVariant.SLIM : SkinVariant.CLASSIC;
        SkinIdentifier want = SkinIdentifier.ofURL(r.url, v);
        if (cur.isPresent() && cur.get().equals(want)) return;   // уже стоит: SR сам применил при входе
        if (!sr.getSkinStorage().getSkinDataByIdentifier(want).isPresent())
            sr.getSkinStorage().setURLSkinByResponse(r.url, sr.getMineSkinAPI().genSkin(r.url, v));
        ps.setSkinIdOfPlayer(id, want);
        reapply(p);
        log.fine("Скины: " + name + ": поставлен скин CraftRadar");
    }

    /** Не Bukkit.getPlayer(UUID): его нет в API 1.6.4/1.7.2, против которых компилируется клей (CI compile-matrix). */
    private void reapply(Player p) throws Exception {
        if (p.isOnline()) sr.getSkinApplier(Player.class).applySkin(p);
    }
}
