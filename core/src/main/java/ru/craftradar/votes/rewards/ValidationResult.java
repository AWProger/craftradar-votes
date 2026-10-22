package ru.craftradar.votes.rewards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Итог проверки конфига: либо настройки, либо список ошибок с путями (rewards.default.items[0].material: ...). */
public final class ValidationResult {
    private final RewardsSettings settings;
    private final List<String> errors;

    public ValidationResult(RewardsSettings settings, List<String> errors) {
        this.settings = settings;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }
    public RewardsSettings settings() { return settings; }
    public List<String> errors() { return errors; }
    public boolean ok() { return errors.isEmpty() && settings != null; }

    @Override public boolean equals(Object o) {
        return o instanceof ValidationResult && Objects.equals(settings, ((ValidationResult) o).settings) && errors.equals(((ValidationResult) o).errors);
    }
    @Override public int hashCode() { return Objects.hash(settings, errors); }
    @Override public String toString() { return "ValidationResult[settings=" + settings + ", errors=" + errors + "]"; }
}
