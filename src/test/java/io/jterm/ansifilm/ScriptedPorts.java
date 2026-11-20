package io.jterm.ansifilm;

import io.jterm.core.input.KeyStroke;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared law-test fixture: a manual pacing clock. */
final class ScriptedClock implements AnsiFilmScreen.FrameClock {
    long nowMillis;

    @Override
    public long nowMillis() {
        return nowMillis;
    }
}

/** Shared law-test fixture: a scripted input port that records poll budgets. */
final class ScriptedInput implements AnsiFilmScreen.FilmInput {
    final List<Long> timeouts = new ArrayList<>();
    final List<KeyStroke> queue = new ArrayList<>();
    private final Runnable onEmpty;

    /**
     * Creates the port; {@code onEmpty} runs when the queue is dry (usually
     * advancing the companion clock by the full budget, simulating the wait).
     *
     * @param onEmpty the wait-side effect
     */
    ScriptedInput(Runnable onEmpty) {
        this.onEmpty = onEmpty;
    }

    @Override
    public Optional<KeyStroke> poll(long timeoutMillis) {
        timeouts.add(timeoutMillis);
        if (queue.isEmpty()) {
            onEmpty.run(); // the wait burns the remaining frame time
            return Optional.empty();
        }
        return Optional.of(queue.remove(0));
    }
}
