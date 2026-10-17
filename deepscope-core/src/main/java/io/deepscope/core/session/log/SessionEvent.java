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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One entry in the append-only session log: the durable source of truth for an agent interaction.
 *
 * <p>Model history is derived from committed entries. Sequence numbers are contiguous and equal the
 * log length, so an event at position {@code n} always follows an event at position {@code n - 1}.
 *
 * <p>Surface metadata is conditional by construction. A message-producing event requires a
 * {@link SurfaceOp}; a log-only event must not carry one. Construction enforces this, so no reader
 * has to guess whether a boundary marker was meant to be a message.
 *
 * @param seq monotonically increasing position within the session
 * @param time Unix epoch milliseconds at append
 * @param type the event type discriminator
 * @param data the event payload
 * @param surfaceOp placement on the model-visible surface; null exactly for log-only events
 * @param sourceEventSeqs complete, non-empty set of earlier event positions this event derives from
 *     or shadowed; empty when the event cites none
 * @param ignorable whether a reader that does not recognize {@code type} may skip this record
 * @param eventId caller identity of this record; null when the kernel assigned none
 * @param executionRunId execution that wrote this record; null when the event has none
 * @param turnId turn that owns this record; null when the event has none
 */
public record SessionEvent(
        SessionSeq.Seq seq,
        long time,
        String type,
        SessionEventData data,
        SurfaceOp surfaceOp,
        List<SessionSeq.Seq> sourceEventSeqs,
        boolean ignorable,
        String eventId,
        String executionRunId,
        String turnId) {

    public SessionEvent {
        Objects.requireNonNull(seq, "seq");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        if (time < 0) {
            throw new IllegalArgumentException("Event time must be non-negative, got " + time);
        }
        if (!type.equals(data.type())) {
            throw new IllegalArgumentException(
                    "Event type \""
                            + type
                            + "\" does not match its payload type \""
                            + data.type()
                            + "\"");
        }
        sourceEventSeqs = sourceEventSeqs == null ? List.of() : List.copyOf(sourceEventSeqs);
        requireSourceEventSeqs(sourceEventSeqs, seq);
        if (ignorable && SessionEventTypes.isKnown(type)) {
            throw new IllegalArgumentException(
                    "session event \""
                            + type
                            + "\" is a catalogued type and must not be marked ignorable");
        }
        eventId = blankToNull(eventId);
        executionRunId = blankToNull(executionRunId);
        turnId = blankToNull(turnId);
    }

    /**
     * An event recorded before caller identity was stored on the kernel record.
     */
    public SessionEvent(
            SessionSeq.Seq seq,
            long time,
            String type,
            SessionEventData data,
            SurfaceOp surfaceOp,
            List<SessionSeq.Seq> sourceEventSeqs,
            boolean ignorable) {
        this(seq, time, type, data, surfaceOp, sourceEventSeqs, ignorable, null, null, null);
    }

    /** The same event, carrying the caller's record identity. */
    public SessionEvent withIdentity(String eventId, String executionRunId, String turnId) {
        return new SessionEvent(
                seq,
                time,
                type,
                data,
                surfaceOp,
                sourceEventSeqs,
                ignorable,
                eventId,
                executionRunId,
                turnId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Create a message-producing event.
     *
     * @param seq position in the log
     * @param time Unix epoch milliseconds
     * @param data a message-producing payload
     * @param surfaceOp how the event enters the model-visible surface
     * @param sourceEventSeqs earlier events this one derives from or shadowed
     * @return the event
     */
    public static SessionEvent surface(
            SessionSeq.Seq seq,
            long time,
            SessionEventData.SurfaceData data,
            SurfaceOp surfaceOp,
            List<SessionSeq.Seq> sourceEventSeqs) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(surfaceOp, "surfaceOp");
        if (data instanceof SessionEventData.AssistantMessageData
                && sourceEventSeqs != null
                && !sourceEventSeqs.isEmpty()) {
            throw new IllegalArgumentException(
                    "assistant/message embeds its source stream and cannot cite sourceEventSeqs");
        }
        return new SessionEvent(seq, time, data.type(), data, surfaceOp, sourceEventSeqs, false);
    }

    /**
     * Create a log-only event that produces no model-visible message.
     *
     * @param seq position in the log
     * @param time Unix epoch milliseconds
     * @param data a log-only payload
     * @return the event
     */
    public static SessionEvent logOnly(SessionSeq.Seq seq, long time, SessionEventData data) {
        Objects.requireNonNull(data, "data");
        return new SessionEvent(seq, time, data.type(), data, null, List.of(), false);
    }

    /**
     * Reconstruct an event from stored fields, applying the same event-local validation as append.
     *
     * <p>References to other events are not checked here: a stored record may cite history outside
     * the loaded window, and membership is the fold's responsibility.
     *
     * @param seq stored position
     * @param time stored timestamp
     * @param data stored payload
     * @param surfaceOp stored surface operation
     * @param sourceEventSeqs stored source positions
     * @param ignorable stored skip marker
     * @return the restored event
     */
    public static SessionEvent restore(
            SessionSeq.Seq seq,
            long time,
            SessionEventData data,
            SurfaceOp surfaceOp,
            List<SessionSeq.Seq> sourceEventSeqs,
            boolean ignorable) {
        Objects.requireNonNull(data, "data");
        boolean eligible = SessionEventTypes.isSurfaceEligible(data.type());
        if (eligible && surfaceOp == null) {
            throw new IllegalArgumentException(
                    "session event \""
                            + data.type()
                            + "\" is surface-eligible and requires a surfaceOp marker");
        }
        if (!eligible && surfaceOp != null) {
            throw new IllegalArgumentException(
                    "session event \""
                            + data.type()
                            + "\" is not surface-eligible and cannot carry a surfaceOp");
        }
        return new SessionEvent(
                seq, time, data.type(), data, surfaceOp, sourceEventSeqs, ignorable);
    }

    /** Whether this event carries a surface operation, and therefore produces a message. */
    public boolean isSurface() {
        return surfaceOp != null;
    }

    /** Whether this event entered the surface by appending to its tail. */
    public boolean isAppendSurface() {
        return surfaceOp instanceof SurfaceOp.Append;
    }

    /** Whether this event shadowed an existing surface range instead of appending. */
    public boolean isReplacementSurface() {
        return surfaceOp instanceof SurfaceOp.Replace;
    }

    private static void requireSourceEventSeqs(
            List<SessionSeq.Seq> sources, SessionSeq.Seq currentSeq) {
        if (sources.isEmpty()) {
            return;
        }
        Set<SessionSeq.Seq> seen = new LinkedHashSet<>();
        for (SessionSeq.Seq source : sources) {
            Objects.requireNonNull(source, "sourceEventSeqs entry");
            if (!seen.add(source)) {
                throw new IllegalArgumentException("sourceEventSeqs must not contain duplicates");
            }
            if (source.compareTo(currentSeq) >= 0) {
                throw new IllegalArgumentException(
                        "sourceEventSeqs must reference earlier events: "
                                + source
                                + " >= current seq "
                                + currentSeq);
            }
        }
    }
}
