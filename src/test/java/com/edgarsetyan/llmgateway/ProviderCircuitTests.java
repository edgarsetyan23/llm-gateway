package com.edgarsetyan.llmgateway;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCircuitTests {
    private final TestClock clock = new TestClock();

    @Test void onlyOneProbeIsAdmittedAfterCooldown() {
        var circuit = new ProviderCircuit(1, 30, clock);
        circuit.complete(circuit.acquire(), false);
        assertNull(circuit.acquire());
        clock.advanceSeconds(30);
        var probe = circuit.acquire();
        assertNotNull(probe);
        assertNull(circuit.acquire());
        circuit.complete(probe, true);
        assertNotNull(circuit.acquire());
    }
    @Test void failedProbeRestartsCooldown() {
        var circuit = new ProviderCircuit(1, 30, clock);
        circuit.complete(circuit.acquire(), false);
        clock.advanceSeconds(30);
        circuit.complete(circuit.acquire(), false);
        clock.advanceSeconds(29);
        assertNull(circuit.acquire());
        clock.advanceSeconds(1);
        assertNotNull(circuit.acquire());
    }
    @Test void successResetsConsecutiveFailureCount() {
        var circuit = new ProviderCircuit(2, 30, clock);
        circuit.complete(circuit.acquire(), false);
        circuit.complete(circuit.acquire(), true);
        circuit.complete(circuit.acquire(), false);
        var permit = circuit.acquire();
        assertNotNull(permit);
        circuit.complete(permit, false);
        assertNull(circuit.acquire());
    }
    @Test void olderInFlightSuccessCannotCloseAnOpenCircuit() {
        var circuit = new ProviderCircuit(1, 30, clock);
        var older = circuit.acquire();
        circuit.complete(circuit.acquire(), false);
        circuit.complete(older, true);
        assertNull(circuit.acquire());
        clock.advanceSeconds(30);
        var probe = circuit.acquire();
        circuit.complete(older, true);
        assertNull(circuit.acquire());
        circuit.complete(probe, true);
        assertNotNull(circuit.acquire());
    }
    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
