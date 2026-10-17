/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.core.session.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the projection seam: domains fold committed events into current values that carriers read
 * without replaying the log themselves.
 *
 * <p>A projection is driven eagerly, so its value is current by construction. A listener hears only
 * about actual changes, and every snapshot names the log position it reflects so a carrier can pair
 * state with the matching history cut.
 */
class ProjectionRegistryTest {

    private static final long TIME = 1_700_000_000_000L;

    /** A key one unit publishes. */
    private record Counter(int count) {}

    /** A unit counting lifecycle events, to exercise unrelated-event handling. */
    private static ProjectionRegistry.Unit<Counter> counterUnit() {
        return new ProjectionRegistry.Unit<>() {
            @Override
            public String key() {
                return "counter";
            }

            @Override
            public long stateVersion() {
                return 1L;
            }

            @Override
            public Counter init(SessionLog log) {
                return new Counter(0);
            }

            @Override
            public Counter apply(Counter state, SessionEvent event) {
                if (event.data() instanceof SessionExecutionData.StepStart) {
                    return new Counter(state.count() + 1);
                }
                // An unrelated event must return the same reference, so the registry can skip work.
                return state;
            }
        };
    }

    private static SessionLog logWithTwoSteps() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepEnd(1L, 1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 2L));
        return log;
    }

    @Test
    @DisplayName("a registered unit's value is current for the committed log")
    void registeredUnitIsCurrent() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit());

        assertEquals(new Counter(2), registry.stateOf(log, "counter"));
    }

    @Test
    @DisplayName("registering a unit folds the log it is first read against")
    void lateRegistrationFoldsHistory() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();

        registry.register(counterUnit());

        assertEquals(new Counter(2), registry.stateOf(log, "counter"));
    }

    @Test
    @DisplayName("appending continues the fold without re-reading folded events")
    void appendingContinuesTheFold() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = new SessionLog();
        registry.register(counterUnit());
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        assertEquals(new Counter(1), registry.stateOf(log, "counter"));

        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 2L));

        assertEquals(new Counter(2), registry.stateOf(log, "counter"));
    }

    @Test
    @DisplayName("a listener hears about a change with its log position")
    void listenerHearsChanges() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = new SessionLog();
        registry.register(counterUnit());
        List<ProjectionRegistry.Change<Counter>> heard = new ArrayList<>();
        registry.onChange(
                log, "counter", (ProjectionRegistry.Change<Counter> change) -> heard.add(change));

        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        // Reading is what drives the fold over committed events.
        registry.stateOf(log, "counter");

        assertEquals(1, heard.size());
        assertEquals(new Counter(1), heard.get(0).value());
        assertEquals(0, heard.get(0).asOfSeq());
    }

    @Test
    @DisplayName("a listener hears nothing when the value does not change")
    void listenerHearsNothingOnUnchangedValue() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = new SessionLog();
        registry.register(counterUnit());
        List<ProjectionRegistry.Change<Counter>> heard = new ArrayList<>();
        registry.onChange(
                log, "counter", (ProjectionRegistry.Change<Counter> change) -> heard.add(change));

        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        registry.stateOf(log, "counter");

        assertEquals(0, heard.size(), "an event the unit ignores publishes no change");

        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        registry.stateOf(log, "counter");
        assertEquals(1, heard.size(), "a consumed event publishes one change");

        log.appendLogOnly(
                TIME,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));
        registry.stateOf(log, "counter");
        assertEquals(1, heard.size(), "a later ignored event publishes nothing");
    }

    @Test
    @DisplayName("a snapshot reports the position it reflects for every requested key")
    void snapshotNamesItsPosition() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit());

        ProjectionRegistry.Snapshot snapshot = registry.snapshot(log, List.of("counter"));

        assertEquals(3L, snapshot.asOfSeq(), "the snapshot names the last folded event");
        assertEquals(new Counter(2), snapshot.values().get("counter"));
    }

    @Test
    @DisplayName("a snapshot of an empty log names no event")
    void snapshotOfEmptyLogNamesNoEvent() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());

        ProjectionRegistry.Snapshot snapshot =
                registry.snapshot(new SessionLog(), List.of("counter"));

        assertFalse(snapshot.asOfSeq() >= 0);
        assertEquals(new Counter(0), snapshot.values().get("counter"));
    }

    @Test
    @DisplayName("an unregistered key is absent instead of invented")
    void unregisteredKeyIsAbsent() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = new SessionLog();

        assertNull(registry.stateOf(log, "missing"));
        assertTrue(registry.snapshot(log, List.of("missing")).values().isEmpty());
    }

    @Test
    @DisplayName("a snapshot of a subset of keys carries only those keys")
    void snapshotCarriesOnlyRequestedKeys() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit());
        registry.register(
                new ProjectionRegistry.Unit<Map<String, Object>>() {
                    @Override
                    public String key() {
                        return "other";
                    }

                    @Override
                    public long stateVersion() {
                        return 1L;
                    }

                    @Override
                    public Map<String, Object> init(SessionLog target) {
                        return Map.of();
                    }

                    @Override
                    public Map<String, Object> apply(
                            Map<String, Object> state, SessionEvent event) {
                        return state;
                    }
                });

        ProjectionRegistry.Snapshot snapshot = registry.snapshot(log, List.of("counter"));

        assertEquals(1, snapshot.values().size());
        assertTrue(snapshot.values().containsKey("counter"));
    }

    @Test
    @DisplayName("a duplicate key is rejected")
    void duplicateKeyIsRejected() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());

        assertThrows(IllegalArgumentException.class, () -> registry.register(counterUnit()));
    }

    @Test
    @DisplayName("unregistering removes the key and disposes its listener")
    void unregisteringRemovesTheKey() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = new SessionLog();
        ProjectionRegistry.Registration registration = registry.register(counterUnit());
        List<ProjectionRegistry.Change<Counter>> heard = new ArrayList<>();
        registry.onChange(
                log, "counter", (ProjectionRegistry.Change<Counter> change) -> heard.add(change));

        registration.dispose();

        assertNull(registry.stateOf(log, "counter"));
        assertTrue(registration.isDisposed());
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        assertEquals(0, heard.size(), "a disposed listener stops hearing changes");
    }

    @Test
    @DisplayName("disposing twice is safe")
    void disposingTwiceIsSafe() {
        ProjectionRegistry registry = new ProjectionRegistry();
        ProjectionRegistry.Registration registration = registry.register(counterUnit());

        registration.dispose();
        registration.dispose();

        assertTrue(registration.isDisposed());
    }

    @Test
    @DisplayName("re-registering a key after disposal works")
    void reRegisteringAfterDisposalWorks() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit()).dispose();

        registry.register(counterUnit());

        assertEquals(new Counter(2), registry.stateOf(log, "counter"));
    }

    @Test
    @DisplayName("each session folds independently")
    void sessionsFoldIndependently() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());
        SessionLog first = logWithTwoSteps();
        SessionLog second = new SessionLog();
        second.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));

        assertEquals(new Counter(2), registry.stateOf(first, "counter"));
        assertEquals(new Counter(1), registry.stateOf(second, "counter"));
    }

    @Test
    @DisplayName("a key with no requested snapshot key reports an empty value map")
    void snapshotWithNoKeysIsEmpty() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());

        assertTrue(registry.snapshot(new SessionLog(), List.of()).values().isEmpty());
    }

    @Test
    @DisplayName("state is read-only: mutating a returned map does not change the registry")
    void returnedValuesAreDefensive() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit());

        ProjectionRegistry.Snapshot first = registry.snapshot(log, List.of("counter"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.values().put("counter", new Counter(99)));
        assertEquals(new Counter(2), registry.stateOf(log, "counter"));
    }

    @Test
    @DisplayName("a unit's own snapshot value is stable between reads")
    void unitValueIsStableBetweenReads() {
        ProjectionRegistry registry = new ProjectionRegistry();
        SessionLog log = logWithTwoSteps();
        registry.register(counterUnit());

        Counter first = registry.stateOf(log, "counter");
        Counter second = registry.stateOf(log, "counter");

        assertSame(first, second, "an unchanged value keeps its identity");
    }

    @Test
    @DisplayName("a projection of one session does not see another session's events")
    void projectionsDoNotLeakAcrossSessions() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());
        SessionLog observed = new SessionLog();
        SessionLog other = logWithTwoSteps();

        assertEquals(new Counter(0), registry.stateOf(observed, "counter"));
        assertEquals(new Counter(2), registry.stateOf(other, "counter"));
        assertEquals(new Counter(0), registry.stateOf(observed, "counter"));
    }

    @Test
    @DisplayName("a unit's declared state version is reported")
    void stateVersionIsReported() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(counterUnit());

        assertEquals(Optional.of(1L), registry.stateVersionOf("counter"));
        assertEquals(Optional.empty(), registry.stateVersionOf("missing"));
    }
}
