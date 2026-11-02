package ru.craftradar.votes;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Единственный класс, который ссылается на API Votifier/NuVotifier: регистрируется, только если Votifier включён,
 * поэтому без NuVotifier плагин загружается без NoClassDefFoundError. Событие только читается (MONITOR, без отмены и правок).
 */
final class VotifierListener implements Listener {
    private final CraftRadarVotesPlugin plugin;

    VotifierListener(CraftRadarVotesPlugin plugin) { this.plugin = plugin; }

    /** Votifier classic (1.9) не знает additionalData (это NuVotifier v2): тогда null, как у голоса v1. */
    private static byte[] additionalData(Vote v) {
        try { return v.getAdditionalData(); } catch (NoSuchMethodError | AbstractMethodError e) { return null; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onVote(VotifierEvent e) {
        Vote v = e.getVote();
        plugin.onVotifierVote(v.getServiceName(), v.getUsername(), v.getAddress(), v.getTimeStamp(), additionalData(v));
    }
}
