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
package io.agentscope.core.session.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of log-derived values a carrier reads without replaying the log.
 *
 * <p>A domain contributes one {@link Unit} per state key. The registry drives each unit over
 * committed events on read, so a unit's value is current by construction rather than by a caller
 * remembering to refresh it. A unit that does not care about an event returns its state unchanged,
 * and the registry skips both the comparison and the notification.
 *
 * <p>A snapshot names the log position it reflects, so a carrier can pair a value with the matching
 * history cut instead of assuming the value is current.
 */
public final class ProjectionRegistry {

    /** A domain's fold from committed events to one current value. */
    public interface Unit<S> {

        /** The state key this unit publishes. */
        String key();

        /**
         * The unit's state version, which a checkpoint stores so a stale cached value is discarded
         * rather than reinterpreted.
         *
         * @return a positive version, bumped whenever the state fields or fold semantics change
         */
        long stateVersion();

        /**
         * The value before any committed event.
         *
         * @param log the session being folded, for state that depends only on the log's identity
         * @return the initial value
         */
        S init(SessionLog log);

        /**
         * Fold one committed event.
         *
         * <p>Implementations must be synchronous and pure, and must return the same reference for an
         * event they do not consume.
         *
         * @param state the value before this event
         * @param event the committed event
         * @return the value after this event
         */
        S apply(S state, SessionEvent event);
    }

    /** A contribution handle; disposing removes the unit and its listeners. */
    public static final class Registration {

        private final Unit<?> unit;
        private final ProjectionRegistry owner;
        private boolean disposed;

        private Registration(Unit<?> unit, ProjectionRegistry owner) {
            this.unit = unit;
            this.owner = owner;
        }

        /** The unit's key. */
        public String key() {
            return unit.key();
        }

        /** Whether this contribution has been removed. */
        public boolean isDisposed() {
            return disposed;
        }

        /** Remove the unit and every listener registered for its key. */
        public void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            owner.remove(unit.key());
        }
    }

    /** One published value and the log position it reflects. */
    public record Change<S>(S value, long asOfSeq) {}

    /**
     * Receives a key's changed values.
     *
     * <p>A named interface rather than a bare {@code Consumer}: a generic functional interface makes
     * the compiler infer the value type as {@code Object} at a method-reference call site, which
     * turns an otherwise correct listener into a compile error.
     *
     * @param <S> the projection's state type
     */
    @FunctionalInterface
    public interface ChangeListener<S> {
        /**
         * Handle one changed value.
         *
         * @param change the new value and the log position it reflects
         */
        void changed(Change<S> change);
    }

    /** A set of values read at one consistent log position. */
    public record Snapshot(Map<String, Object> values, long asOfSeq) {
        public Snapshot {
            values = Map.copyOf(values);
        }
    }

    private final Map<String, Unit<?>> units = new LinkedHashMap<>();
    private final Map<SessionLog, Map<String, Cell<?>>> cells = new IdentityHashMap<>();
    private final Map<String, List<Subscriber>> listeners = new HashMap<>();

    /** One subscription, erased to a single dispatch method. */
    private record Subscriber(ChangeListener<?> listener) {
        private void accept(Object value, long asOfSeq) {
            dispatch(listener, value, asOfSeq);
        }

        @SuppressWarnings("unchecked")
        private static <S> void dispatch(ChangeListener<S> listener, Object value, long asOfSeq) {
            listener.changed(new Change<>((S) value, asOfSeq));
        }
    }

    /** One session's folded state for one unit. */
    private static final class Cell<S> {
        private S value;
        private long generation;

        private Cell(S value) {
            this.value = value;
        }
    }

    /**
     * Contribute a unit.
     *
     * @param unit the fold to register
     * @return a handle whose disposal removes the contribution
     * @throws IllegalArgumentException when the key is already registered
     */
    public <S> Registration register(Unit<S> unit) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(unit.key(), "unit key");
        if (units.containsKey(unit.key())) {
            throw new IllegalArgumentException(
                    "Projection key is already registered: " + unit.key());
        }
        if (unit.stateVersion() <= 0) {
            throw new IllegalArgumentException(
                    "Projection " + unit.key() + " must declare a positive state version");
        }
        units.put(unit.key(), unit);
        cells.values().forEach(byKey -> byKey.remove(unit.key()));
        return new Registration(unit, this);
    }

    /**
     * The current value for one key, folding any event committed since the last read.
     *
     * @param log the session to read
     * @param key the state key
     * @return the current value, or null when no unit publishes that key
     */
    public <S> S stateOf(SessionLog log, String key) {
        return cellOf(log, key, true) == null ? null : valueOf(log, key);
    }

    /**
     * Read several keys at one consistent log position.
     *
     * @param log the session to read
     * @param keys the state keys to include
     * @return the values and the last position they reflect
     */
    public Snapshot snapshot(SessionLog log, List<String> keys) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(keys, "keys");
        Map<String, Object> values = new LinkedHashMap<>();
        long position = Long.MAX_VALUE;
        for (String key : keys) {
            Unit<?> unit = units.get(key);
            if (unit == null) {
                continue;
            }
            Object value = valueOf(log, key);
            values.put(key, value);
            position = Math.min(position, cellOf(log, key, false).generation);
        }
        long asOfSeq = position == Long.MAX_VALUE ? -1L : position - 1;
        return new Snapshot(values, asOfSeq);
    }

    /**
     * Subscribe to a key's changes.
     *
     * <p>A change is published only when the value differs from the last published one, so a
     * listener's work stays proportional to real state movement.
     *
     * @param log the session to observe
     * @param key the state key
     * @param listener receives each changed value with its log position
     * @return a handle whose disposal stops the subscription
     */
    @SuppressWarnings("unchecked")
    public <S> RegistrationHandle onChange(SessionLog log, String key, ChangeListener<S> listener) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(listener, "listener");
        Subscriber subscription = new Subscriber(listener);
        listeners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(subscription);
        return new RegistrationHandle(
                () -> listeners.getOrDefault(key, List.of()).remove(subscription));
    }

    /** A subscription handle. */
    public static final class RegistrationHandle {
        private final Runnable disposer;

        private RegistrationHandle(Runnable disposer) {
            this.disposer = disposer;
        }

        /** Stop the subscription. */
        public void dispose() {
            disposer.run();
        }
    }

    /**
     * The declared state version of a key.
     *
     * @param key the state key
     * @return the version, or empty when no unit publishes that key
     */
    public Optional<Long> stateVersionOf(String key) {
        Unit<?> unit = units.get(key);
        return unit == null ? Optional.empty() : Optional.of(unit.stateVersion());
    }

    private void remove(String key) {
        units.remove(key);
        cells.values().forEach(byKey -> byKey.remove(key));
        listeners.remove(key);
    }

    @SuppressWarnings("unchecked")
    private <S> S valueOf(SessionLog log, String key) {
        Cell<S> cell = (Cell<S>) cellOf(log, key, true);
        return cell == null ? null : cell.value;
    }

    @SuppressWarnings("unchecked")
    private <S> Cell<S> cellOf(SessionLog log, String key, boolean fold) {
        Unit<S> unit = (Unit<S>) units.get(key);
        if (unit == null) {
            return null;
        }
        Map<String, Cell<?>> byKey = cells.computeIfAbsent(log, ignored -> new HashMap<>());
        Cell<S> cell = (Cell<S>) byKey.get(key);
        if (cell == null) {
            cell = new Cell<>(unit.init(log));
            byKey.put(key, cell);
        }
        if (fold) {
            fold(log, unit, cell);
        }
        return cell;
    }

    private <S> void fold(SessionLog log, Unit<S> unit, Cell<S> cell) {
        List<SessionEvent> events = log.events();
        boolean changed = false;
        for (long seq = cell.generation; seq < events.size(); seq++) {
            S next = unit.apply(cell.value, events.get((int) seq));
            if (next != cell.value) {
                cell.value = next;
                changed = true;
            }
        }
        cell.generation = events.size();
        if (changed) {
            publish(unit.key(), cell.value, cell.generation - 1);
        }
    }

    private void publish(String key, Object value, long asOfSeq) {
        for (Subscriber subscriber : List.copyOf(listeners.getOrDefault(key, List.of()))) {
            subscriber.accept(value, asOfSeq);
        }
    }
}
