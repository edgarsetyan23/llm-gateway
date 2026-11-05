package com.edgarsetyan.llmgateway;

import java.time.Clock;

/** One circuit per configured provider. State transitions are guarded by this monitor. */
final class ProviderCircuit {
    record Permit(long generation) {}
    private enum State { CLOSED, OPEN, HALF_OPEN }
    private final int threshold;
    private final long cooldownMillis;
    private final Clock clock;
    private State state = State.CLOSED;
    private int failures;
    private long openedAt, generation;

    ProviderCircuit(int threshold, int cooldownSeconds, Clock clock) {
        this.threshold = threshold;
        this.cooldownMillis = cooldownSeconds * 1000L;
        this.clock = clock;
    }

    synchronized Permit acquire() {
        if (state == State.HALF_OPEN) return null;
        if (state == State.OPEN) {
            if (clock.millis() - openedAt < cooldownMillis) return null;
            state = State.HALF_OPEN;
            generation++;
        }
        return new Permit(generation);
    }

    synchronized void complete(Permit permit, boolean reachable) {
        // An older in-flight call must not undo a newer open/probe transition.
        if (permit.generation != generation) return;
        if (reachable) {
            failures = 0;
            if (state == State.HALF_OPEN) generation++;
            state = State.CLOSED;
        } else if (state == State.HALF_OPEN || ++failures >= threshold) {
            state = State.OPEN;
            openedAt = clock.millis();
            generation++;
        }
    }
}
