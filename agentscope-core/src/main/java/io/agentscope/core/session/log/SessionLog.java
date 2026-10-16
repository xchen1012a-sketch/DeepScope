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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An append-only log of session events, plus the model-visible surface derived from it.
 *
 * <p>The log is the single source of truth. Model history is derived from committed events rather
 * than stored beside them, so a reader that has the log can reconstruct exactly what the model saw.
 *
 * <p>Three invariants hold after every operation:
 *
 * <ol>
 *   <li>Committed events are never rewritten, and sequence numbers are contiguous from zero.
 *   <li>A rejected append changes nothing: not the log, not the surface, not the derived history.
 *   <li>Only message-producing events reach the derived history, and only through their recorded
 *       surface placement.
 * </ol>
 *
 * <p>Instances are not thread-safe. A session has exactly one writer; readers observe committed
 * state only.
 */
public final class SessionLog {

    private final List<SessionEvent> events = new ArrayList<>();
    private final SurfaceFold surface = new SurfaceFold();
    private final List<SessionMessageProjection> projections;
    private final Map<String, SessionMessageProjection> projectionsByType = new HashMap<>();
    private final Map<SessionSeq.Seq, Msg> projectedMessages = new HashMap<>();
    private List<Msg> derivedCache;
    private final Map<SessionSeq.Seq, Msg> nodeCache = new HashMap<>();

    /** Create an empty log. */
    public SessionLog() {
        this(List.of());
    }

    /**
     * Create an empty log with extension message projections.
     *
     * @param projections pure interpreters for extension events that change message content
     */
    public SessionLog(List<SessionMessageProjection> projections) {
        this.projections = List.copyOf(projections == null ? List.of() : projections);
        for (SessionMessageProjection projection : this.projections) {
            SessionMessageProjection previous =
                    projectionsByType.put(projection.type(), projection);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate message projection for event type \""
                                + projection.type()
                                + "\"");
            }
        }
    }

    /**
     * Append a message-producing event at the next position.
     *
     * @param time Unix epoch milliseconds
     * @param data the message-producing payload
     * @param surfaceOp how the event enters the model-visible surface
     * @param sourceEventSeqs earlier events this one derives from or shadowed; empty when none
     * @return the committed event
     * @throws IllegalArgumentException when the append is rejected; the log is unchanged
     */
    public SessionEvent append(
            long time,
            SessionEventData.SurfaceData data,
            SurfaceOp surfaceOp,
            List<SessionSeq.Seq> sourceEventSeqs) {
        Objects.requireNonNull(data, "data");
        return commit(SessionEvent.surface(nextSeq(), time, data, surfaceOp, sourceEventSeqs));
    }

    /**
     * Validate an event as if it were appended next, without committing it.
     *
     * <p>A caller that must accept or reject a whole batch before any of it lands uses this to find
     * the rejected member first, because committing an accepted prefix would leave the log holding a
     * record its caller believes was refused.
     *
     * @param data the candidate payload
     * @throws IllegalArgumentException when the candidate would be rejected
     */
    public void validateNext(SessionEventData data) {
        Objects.requireNonNull(data, "data");
        SessionEvent candidate;
        if (SessionEventTypes.isSurfaceEligible(data.type())) {
            candidate =
                    SessionEvent.surface(
                            nextSeq(),
                            0L,
                            (SessionEventData.SurfaceData) data,
                            SurfaceOp.APPEND,
                            List.of());
        } else {
            candidate = SessionEvent.logOnly(nextSeq(), 0L, data);
        }
        surface.validateNext(candidate, events);
    }

    /**
     * Append a prepared payload at the next position, stamping it with the given time.
     *
     * @param data the payload to commit
     * @param time Unix epoch milliseconds
     * @return the committed event
     * @throws IllegalArgumentException when the append is rejected; the log is unchanged
     */
    public SessionEvent append(SessionEventData data, long time) {
        return append(data, time, null, null, null);
    }

    /**
     * Append a prepared payload and keep the caller's record identity.
     *
     * @param data the payload to commit
     * @param time Unix epoch milliseconds
     * @param eventId caller identity, or null
     * @param executionRunId execution that wrote the record, or null
     * @param turnId turn that owns the record, or null
     * @return the committed event
     */
    public SessionEvent append(
            SessionEventData data,
            long time,
            String eventId,
            String executionRunId,
            String turnId) {
        Objects.requireNonNull(data, "data");
        SessionEvent event;
        if (SessionEventTypes.isSurfaceEligible(data.type())) {
            event =
                    SessionEvent.surface(
                            nextSeq(),
                            time,
                            (SessionEventData.SurfaceData) data,
                            SurfaceOp.APPEND,
                            List.of());
        } else {
            event = SessionEvent.logOnly(nextSeq(), time, data);
        }
        if (eventId != null || executionRunId != null || turnId != null) {
            event = event.withIdentity(eventId, executionRunId, turnId);
        }
        return commit(event);
    }

    /**
     * Append a log-only event at the next position.
     *
     * @param time Unix epoch milliseconds
     * @param data the log-only payload
     * @return the committed event
     * @throws IllegalArgumentException when the append is rejected; the log is unchanged
     */
    public SessionEvent appendLogOnly(long time, SessionEventData data) {
        Objects.requireNonNull(data, "data");
        return commit(SessionEvent.logOnly(nextSeq(), time, data));
    }

    /**
     * Adopt an event already carrying its position, as a restore or seed does.
     *
     * @param event the event to commit
     * @return the committed event
     * @throws IllegalArgumentException when the position is not the next one or the event is rejected
     */
    public SessionEvent append(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        return commit(event);
    }

    /**
     * Append a contiguous batch atomically.
     *
     * <p>Every event is validated against the state the batch itself produces, so a rejection
     * anywhere commits nothing: not the accepted prefix, not its surface changes, not its projected
     * content.
     *
     * @param batch events whose first position equals the log length and whose positions are contiguous
     * @throws IllegalArgumentException when any event is rejected; no event from the batch is committed
     */
    public void appendAll(List<SessionEvent> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return;
        }
        List<SessionEvent> staged = new ArrayList<>(events);
        Map<SessionSeq.Seq, Msg> stagedProjections = null;
        try {
            for (SessionEvent event : batch) {
                SessionSeq.Seq expected = SessionSeq.Seq.of(staged.size());
                if (!event.seq().equals(expected)) {
                    throw new IllegalArgumentException(
                            "session event seq "
                                    + event.seq()
                                    + " is not contiguous; expected "
                                    + expected);
                }
                SessionMessageProjection projection = projectionsByType.get(event.type());
                if (projection != null) {
                    Map<SessionSeq.Seq, Msg> updates =
                            projection.project(event, projectionContext(event, staged));
                    if (updates == null || updates.isEmpty()) {
                        throw new IllegalArgumentException(
                                "message projection for \""
                                        + event.type()
                                        + "\" returned no updates");
                    }
                    stagedProjections = merge(stagedProjections, updates);
                }
                if (SessionEventTypes.isSurfaceEligible(event.type())) {
                    surface.validateNext(event, staged);
                }
                staged.add(event);
            }
        } catch (RuntimeException rejection) {
            surface.replay(events);
            throw rejection;
        }
        for (SessionEvent event : batch) {
            if (SessionEventTypes.isSurfaceEligible(event.type())) {
                surface.accept(event, events);
            }
            events.add(event);
        }
        if (stagedProjections != null) {
            projectedMessages.putAll(stagedProjections);
        }
        // A pure append only appends surface nodes, so the message list is rebuilt from the
        // per-node
        // cache and keeps every already-derived message object. A replacement can shadow any
        // position and a projection can rewrite any node, so those invalidate the per-node cache
        // too.
        derivedCache = null;
        for (SessionEvent event : batch) {
            if (event.isReplacementSurface() || stagedProjections != null) {
                nodeCache.clear();
                break;
            }
        }
    }

    /** The number of committed events, which is also the next free sequence number. */
    public long size() {
        return events.size();
    }

    /** The next free position. */
    public SessionSeq.Seq nextSeq() {
        return SessionSeq.Seq.of(events.size());
    }

    /** The position of the last committed event, or {@code -1} when the log is empty. */
    public SessionSeq.Cursor lastSeq() {
        return SessionSeq.Cursor.of(events.size() - 1);
    }

    /** All committed events, in sequence order. */
    public List<SessionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Read one committed event.
     *
     * @param seq the position to read
     * @return the event at that position, or null when no event exists there
     */
    public SessionEvent eventAt(SessionSeq.Seq seq) {
        Objects.requireNonNull(seq, "seq");
        int index = (int) seq.value();
        return index >= 0 && index < events.size() ? events.get(index) : null;
    }

    /** Surface event positions in model-visible order. */
    public List<SessionSeq.Seq> surfaceNodes() {
        return surface.nodes();
    }

    /** Monotonic count of committed positional replacements. */
    public long replaceGeneration() {
        return surface.replaceGeneration();
    }

    /** Monotonic count of committed changes to existing model-visible content. */
    public long contentGeneration() {
        return surface.contentGeneration();
    }

    /**
     * The model history derived from the committed log, in surface order.
     *
     * <p>Unlike {@link #deriveEventMessage(SessionEvent)}, this is the sequence sent to a model: the
     * system prompt first when present, then every surviving surface message.
     *
     * <p>Each surface node is projected once and cached, so appending extends the derived history
     * without rebuilding the messages already derived from it. A fresh list is returned on each call,
     * so a caller cannot mutate committed history through the result while the message objects
     * themselves stay stable across reads.
     *
     * @return the derived model-visible messages
     */
    public List<Msg> deriveMessages() {
        List<Msg> derived = derivedCache;
        if (derived == null) {
            derived = new ArrayList<>();
            for (SessionSeq.Seq node : surface.nodes()) {
                Msg message = projectNode(node);
                if (message != null) {
                    derived.add(message);
                }
            }
            derivedCache = List.copyOf(derived);
        }
        return List.copyOf(derivedCache);
    }

    /**
     * Project one surface node, reusing the projection it was first derived with.
     *
     * <p>A node is projected once so repeated reads return the same message object, which is what
     * lets a consumer hold a message across an append. A content-rewriting projection is the only
     * other source of a projected message.
     */
    private Msg projectNode(SessionSeq.Seq node) {
        if (nodeCache.containsKey(node)) {
            return nodeCache.get(node);
        }
        Msg projected = projectedMessages.get(node);
        Msg derived =
                projected != null
                        ? projected
                        : MessageDerivation.derive(events.get((int) node.value()));
        nodeCache.put(node, derived);
        return derived;
    }

    /**
     * Derive the message one event produces, applying any registered projection.
     *
     * <p>The per-node counterpart of {@link #deriveMessages()}, exposed so an offline reconstructor
     * applying the same rules cannot disagree with the live fold.
     *
     * @param event the event to project
     * @return the derived message, or null when the event produces none
     */
    public Msg deriveEventMessage(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        // A projection applies only to a node that is still on the surface: a shadowed node is no
        // longer model-visible, so projecting it must not resurrect rewritten content.
        return surface.contains(event.seq())
                ? projectNode(event.seq())
                : MessageDerivation.derive(event);
    }

    private SessionEvent commit(SessionEvent event) {
        appendAll(List.of(event));
        return event;
    }

    private SessionMessageProjection.Context projectionContext(
            SessionEvent event, List<SessionEvent> staged) {
        return new SessionMessageProjection.Context(
                surface.nodes(), List.copyOf(staged), Map.copyOf(projectedMessages));
    }

    private static Map<SessionSeq.Seq, Msg> merge(
            Map<SessionSeq.Seq, Msg> target, Map<SessionSeq.Seq, Msg> updates) {
        Map<SessionSeq.Seq, Msg> merged = target == null ? new HashMap<>() : target;
        merged.putAll(updates);
        return merged;
    }
}
