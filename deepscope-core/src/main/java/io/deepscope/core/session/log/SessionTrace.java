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

import java.util.List;
import java.util.Objects;

/**
 * One event's positional relationships: what displaced it, what it displaced, and what it cited.
 *
 * <p>A replacement shadows a surface range instead of deleting it, so a reader asking what happened
 * to a message it saw earlier needs the chain of replacements. This is that answer, derived from the
 * recorded surface operations rather than from the current surface, which no longer shows the
 * shadowed nodes at all.
 *
 * @param seq the traced position
 * @param replacedBy replacements whose shadowed range included this position, in log order
 * @param replacementChain {@code replacedBy} followed transitively, ending at the visible node
 * @param currentSeq the position still visible at the end of the chain, or {@code seq} when nothing
 *     displaced it
 * @param replacedEventSeqs the range this event shadowed, empty when it appended
 * @param replacedRange the inclusive shadowed range, or null when the event appended
 * @param sourceEventSeqs the positions this event cited, in recorded order
 */
public record SessionTrace(
        SessionSeq.Seq seq,
        List<SessionSeq.Seq> replacedBy,
        List<SessionSeq.Seq> replacementChain,
        SessionSeq.Seq currentSeq,
        List<SessionSeq.Seq> replacedEventSeqs,
        Range replacedRange,
        List<SessionSeq.Seq> sourceEventSeqs) {

    /** An inclusive range of shadowed positions. */
    public record Range(SessionSeq.Seq start, SessionSeq.Seq end) {}

    public SessionTrace {
        Objects.requireNonNull(seq, "seq");
        replacedBy = List.copyOf(replacedBy);
        replacementChain = List.copyOf(replacementChain);
        replacedEventSeqs = List.copyOf(replacedEventSeqs);
        sourceEventSeqs = List.copyOf(sourceEventSeqs);
    }

    /**
     * Trace one event within a log.
     *
     * @param events contiguous events in sequence order
     * @param seq the position to trace
     * @return the traced relationships
     * @throws IllegalArgumentException when no event occupies that position
     */
    public static SessionTrace of(List<SessionEvent> events, SessionSeq.Seq seq) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(seq, "seq");
        int index = (int) seq.value();
        if (index < 0 || index >= events.size()) {
            throw new IllegalArgumentException("No event at position " + seq);
        }
        SessionEvent target = events.get(index);

        List<SessionSeq.Seq> replacedBy = new java.util.ArrayList<>();
        for (SessionEvent candidate : events) {
            if (candidate.surfaceOp() instanceof SurfaceOp.Replace replace
                    && contains(replace, seq)) {
                replacedBy.add(candidate.seq());
            }
        }

        List<SessionSeq.Seq> chain = new java.util.ArrayList<>();
        SessionSeq.Seq current = seq;
        boolean advanced = true;
        while (advanced) {
            advanced = false;
            for (SessionEvent candidate : events) {
                if (candidate.seq().compareTo(current) > 0
                        && candidate.surfaceOp() instanceof SurfaceOp.Replace replace
                        && contains(replace, current)) {
                    chain.add(candidate.seq());
                    current = candidate.seq();
                    advanced = true;
                }
            }
        }

        List<SessionSeq.Seq> shadowed = List.of();
        Range range = null;
        if (target.surfaceOp() instanceof SurfaceOp.Replace replace) {
            shadowed = shadowedSeqs(events, replace, target.seq());
            range = new Range(replace.start(), replace.end());
        }

        return new SessionTrace(
                seq, replacedBy, chain, current, shadowed, range, target.sourceEventSeqs());
    }

    /** Whether a replacement's inclusive numeric range covers a position. */
    private static boolean contains(SurfaceOp.Replace replace, SessionSeq.Seq seq) {
        return seq.compareTo(replace.start()) >= 0 && seq.compareTo(replace.end()) <= 0;
    }

    /**
     * The surface nodes a replacement shadowed, in surface order.
     *
     * <p>The fold runs over every event before the replacing one. That is the surface the
     * replacement's endpoints were resolved against: the endpoints may themselves be nodes appended
     * after the range start, so stopping at {@code replace.start()} would resolve them against an
     * incomplete surface.
     *
     * @param events contiguous events in sequence order
     * @param replace the replacement whose shadowed range is wanted
     * @param replacementSeq the position of the replacing event
     * @return the shadowed surface nodes, empty when the endpoints are not resolvable
     */
    private static List<SessionSeq.Seq> shadowedSeqs(
            List<SessionEvent> events, SurfaceOp.Replace replace, SessionSeq.Seq replacementSeq) {
        SurfaceFold fold = new SurfaceFold();
        for (SessionEvent event : events) {
            if (event.seq().compareTo(replacementSeq) >= 0) {
                break;
            }
            if (SessionEventTypes.isSurfaceEligible(event.type())) {
                fold.accept(event, events.subList(0, (int) event.seq().value()));
            }
        }
        List<SessionSeq.Seq> nodes = fold.nodes();
        int startIdx = nodes.indexOf(replace.start());
        int endIdx = nodes.indexOf(replace.end());
        if (startIdx == -1 || endIdx == -1 || startIdx > endIdx) {
            return List.of();
        }
        return List.copyOf(nodes.subList(startIdx, endIdx + 1));
    }
}
