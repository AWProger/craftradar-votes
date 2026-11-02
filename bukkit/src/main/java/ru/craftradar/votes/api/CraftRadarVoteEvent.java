package ru.craftradar.votes.api;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Голос, который CraftRadar Votes собирается наградить. Вызывается на главном потоке перед встроенными наградами.
 * Источник: {@link Source#CRAFTRADAR} — голос с CraftRadar (способ «Наш плагин»), {@link Source#VOTIFIER} — голос другого
 * мониторинга через NuVotifier (только если rewards.handle_votifier_votes разрешает награды).
 * Отмена события (setCancelled(true)) — встроенные награды за этот голос не выдаются; статистику на CraftRadar это не меняет.
 * Одно событие на голос: повтор доставки отсекается до вызова.
 */
public final class CraftRadarVoteEvent extends Event implements Cancellable {
    public enum Source { CRAFTRADAR, VOTIFIER }

    private static final HandlerList HANDLERS = new HandlerList();
    private final String username, serviceName, address, voteId;
    private final long timestampMs;
    private final Source source;
    private boolean cancelled;

    public CraftRadarVoteEvent(String username, String serviceName, String address, long timestampMs, String voteId, Source source) {
        this.username = username; this.serviceName = serviceName; this.address = address;
        this.timestampMs = timestampMs; this.voteId = voteId; this.source = source;
    }

    public String getUsername() { return username; }
    public String getServiceName() { return serviceName; }
    public String getAddress() { return address; }
    public long getTimestampMs() { return timestampMs; }
    /** id голоса CraftRadar (16 hex) или correlation_id из Votifier v2; null, если его нет. */
    public String getVoteId() { return voteId; }
    public Source getSource() { return source; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean c) { cancelled = c; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
