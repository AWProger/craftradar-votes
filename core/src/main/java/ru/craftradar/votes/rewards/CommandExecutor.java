package ru.craftradar.votes.rewards;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Владелец: A5. Исполнение действий награды на главном потоке сервера.
 * Логика без Bukkit: поток и dispatch приходят снаружи (Bukkit-оболочка — в модуле bukkit, CraftRadarVotesPlugin.mainThread).
 * Не бросает исключений наружу; при невалидных данных команда НЕ исполняется (FAILED).
 */
public final class CommandExecutor implements ActionExecutor {
    private static final int MAX_TEXT = 256;

    /** Планировщик главного потока: выполнить задачу там и вернуть результат (блокирующе). */
    public interface MainThread {
        <T> T call(Supplier<T> task) throws Exception;
    }

    private final PlayerGateway gateway;
    private final Predicate<String> dispatch;
    private final MainThread mainThread;

    /** dispatch — консольный dispatchCommand, true/false как у Bukkit. Вызывающий гарантирует главный поток. */
    public CommandExecutor(PlayerGateway gateway, Predicate<String> dispatch) {
        this(gateway, dispatch, new MainThread() {
            @Override public <T> T call(Supplier<T> task) { return task.get(); }
        });
    }

    public CommandExecutor(PlayerGateway gateway, Predicate<String> dispatch, MainThread mainThread) {
        this.gateway = gateway;
        this.dispatch = dispatch;
        this.mainThread = mainThread;
    }

    @Override public ActionResult execute(Action a) {
        try {
            // валидация до переключения потока: невалидное не доходит до сервера вообще
            if (a == null || a.type() == null) return ActionResult.failed("пустое действие");
            if (!PlaceholderGuard.validPlayerName(a.player())) return ActionResult.failed("недопустимый ник");
            String text = null;
            if (a.type() == Action.Type.COMMAND || a.type() == Action.Type.MESSAGE || a.type() == Action.Type.BROADCAST) {
                String bad = checkText(a.text());
                if (bad != null) return ActionResult.failed(bad);
                text = PlaceholderGuard.substitute(a.text(), a.player());
                // после подстановки не должно остаться ни одного {...}
                if (!PlaceholderGuard.unknownPlaceholders(text).isEmpty()) return ActionResult.failed("неизвестный плейсхолдер");
                if (a.type() == Action.Type.COMMAND && (ru.craftradar.votes.J8.blank(text) || text.charAt(0) == '/')) return ActionResult.failed("недопустимая команда");
            }
            final String finalText = text;
            return mainThread.call(() -> run(a, finalText));
        } catch (Throwable t) {
            // detail без текста команды: он может содержать чувствительные аргументы
            return ActionResult.failed("исключение при исполнении: " + t.getClass().getSimpleName());
        }
    }

    /** Исполняется на главном потоке. */
    private ActionResult run(Action a, String text) {
        switch (a.type()) {
            case COMMAND:
                return dispatch.test(text) ? ActionResult.done() : ActionResult.failed("команда не выполнена (dispatchCommand=false)");
            case MESSAGE:
                if (!gateway.isOnline(a.player())) return ActionResult.retryLater("игрок оффлайн", 0);
                gateway.sendMessage(a.player(), text);
                return ActionResult.done();
            case BROADCAST:
                gateway.broadcast(text);
                return ActionResult.done();
            case ITEM: {
                if (a.material() == null || a.amount() < 1) return ActionResult.failed("недопустимый предмет");
                if (!gateway.isOnline(a.player())) return ActionResult.retryLater("игрок оффлайн", a.amount());
                int rest = gateway.giveItems(a.player(), a.material(), a.amount());
                return rest <= 0 ? ActionResult.done() : ActionResult.retryLater("нет места в инвентаре", rest);
            }
            case MONEY: {
                double m = a.money();
                if (Double.isNaN(m) || Double.isInfinite(m) || m <= 0) return ActionResult.failed("недопустимая сумма");
                if (!gateway.hasEconomy()) return ActionResult.retryLater("нет экономики (Vault)", 0);
                return gateway.deposit(a.player(), m) ? ActionResult.done() : ActionResult.failed("экономика отказала");
            }
            default:
                return ActionResult.failed("неизвестный тип действия");
        }
    }

    private static String checkText(String t) {
        if (t == null || t.isEmpty()) return "пустой текст";
        if (t.length() > MAX_TEXT) return "текст длиннее " + MAX_TEXT;
        if (PlaceholderGuard.hasLineBreakOrControl(t)) return "перевод строки или управляющий символ";
        return null;
    }
}
