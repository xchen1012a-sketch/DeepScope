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

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Read-only access to stored sessions: listing, reading, and filtering without replaying them
 * through a writer.
 *
 * <p>Every operation opens a read handle and closes it again, so querying never claims write
 * ownership and never blocks a session's own writer. Values that are derived rather than stored —
 * the title and the whole-session figures — are folded from the same events the reader sees, so a
 * summary cannot disagree with the log it describes.
 */
public final class SessionQuery {

    private final LocalSessionStore store;
    private final ProjectionRegistry projections;

    /**
     * Query one store with the standard projections registered.
     *
     * @param store the store to read
     */
    public SessionQuery(LocalSessionStore store) {
        this(store, standardProjections());
    }

    /**
     * Query one store with a caller-supplied projection registry.
     *
     * @param store the store to read
     * @param projections the registry carrying the units whose values summaries expose
     */
    public SessionQuery(LocalSessionStore store, ProjectionRegistry projections) {
        this.store = Objects.requireNonNull(store, "store");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    /** A registry containing the units a summary needs. */
    private static ProjectionRegistry standardProjections() {
        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(new SessionStatsProjection().unit());
        registry.register(new TurnOutlineProjection().unit());
        return registry;
    }

    /**
     * A stored session's identity, stored size, and derived title and figures.
     *
     * @param sessionId the session id
     * @param eventCount committed events
     * @param title the recorded title, or the fallback, or null when nothing names the session
     * @param stats the whole-session figures
     */
    public record SessionSummary(
            String sessionId,
            long eventCount,
            String title,
            SessionStatsProjection.SessionStats stats) {}

    /** Longest fallback title, in words and bytes. */
    private static final int FALLBACK_MAX_WORDS = 8;

    private static final int FALLBACK_MAX_BYTES = 60;

    /**
     * Every readable stored session id.
     *
     * @return the ids, in order; an unreadable log is omitted rather than failing the sweep
     */
    public List<String> listSessionIds() {
        return store.list();
    }

    /**
     * Summary facts for one stored session.
     *
     * @param sessionId the session to summarise
     * @return the summary
     * @throws LocalSessionStore.SessionNotFoundException when the session is not stored
     */
    public SessionSummary summary(String sessionId) {
        List<SessionEvent> events = readEvents(sessionId);
        LocalSessionStore.Stats stored = store.stat(sessionId);
        return new SessionSummary(
                sessionId,
                stored.eventCount(),
                SessionTitle.effectiveTitle(events, FALLBACK_MAX_WORDS, FALLBACK_MAX_BYTES),
                statsOf(events));
    }

    /**
     * Summaries for every readable stored session, in id order.
     *
     * @return the summaries
     */
    public List<SessionSummary> summaries() {
        List<SessionSummary> summaries = new ArrayList<>();
        for (String sessionId : listSessionIds()) {
            summaries.add(summary(sessionId));
        }
        return List.copyOf(summaries);
    }

    /**
     * Summaries matching a predicate.
     *
     * @param predicate the condition a summary must satisfy
     * @return the matching summaries, in id order
     */
    public List<SessionSummary> findSessions(Predicate<SessionSummary> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return summaries().stream().filter(predicate).toList();
    }

    /**
     * One stored session's committed events.
     *
     * @param sessionId the session to read
     * @return the events in sequence order
     * @throws LocalSessionStore.SessionNotFoundException when the session is not stored
     */
    public List<SessionEvent> readEvents(String sessionId) {
        try (LocalSessionStore.Handle handle =
                store.open(sessionId, LocalSessionStore.Access.READ)) {
            return handle.log().events();
        }
    }

    /**
     * Events of one session matching a predicate, keeping their stored positions.
     *
     * @param sessionId the session to read
     * @param predicate the condition an event must satisfy
     * @return the matching events, in sequence order
     */
    public List<SessionEvent> findEvents(String sessionId, Predicate<SessionEvent> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return readEvents(sessionId).stream().filter(predicate).toList();
    }

    /**
     * One stored session's derived model history.
     *
     * @param sessionId the session to read
     * @return the derived messages, in surface order
     */
    public List<Msg> derivedMessages(String sessionId) {
        try (LocalSessionStore.Handle handle =
                store.open(sessionId, LocalSessionStore.Access.READ)) {
            return handle.log().deriveMessages();
        }
    }

    /**
     * Stored metadata for one session.
     *
     * @param sessionId the session to inspect
     * @return the stored stats, including the format version
     */
    public LocalSessionStore.Stats stat(String sessionId) {
        return store.stat(sessionId);
    }

    private SessionStatsProjection.SessionStats statsOf(List<SessionEvent> events) {
        SessionLog log = new SessionLog();
        log.appendAll(events);
        SessionStatsProjection.State state = projections.stateOf(log, SessionStatsProjection.KEY);
        return state == null ? SessionStatsProjection.SessionStats.empty() : state.totals();
    }
}
