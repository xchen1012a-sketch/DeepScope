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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ordered model-visible surface, derived from the session log.
 *
 * <p>The surface is a fold, never a rewrite: appending adds a node at the tail, and replacing
 * shadows an existing range while inserting the replacing event in its place. Shadowed events stay
 * in the log, so a human transcript can still read what the model no longer sees.
 *
 * <p>Every transition is planned before it is committed. A plan validates the candidate against the
 * committed surface and the events before it; a failure leaves the fold untouched, so a rejected
 * event cannot leave a half-applied surface behind.
 *
 * <p>Instances are not thread-safe. Confine one fold to a single writer, which is also the log's
 * concurrency rule.
 */
public final class SurfaceFold {

    private final List<SessionSeq.Seq> nodes = new ArrayList<>();
    private long replaceGeneration;
    private long contentGeneration;

    /** An existing surface node and its index in current surface order. */
    private record NodeIndex(int startIdx, int endIdx, List<SessionSeq.Seq> shadowedSeqs) {}

    /** A validated transition, ready to commit. */
    private sealed interface Plan permits AppendPlan, ReplacePlan {

        /** Apply this transition to the fold. */
        void commit(SurfaceFold fold);
    }

    private record AppendPlan(SessionSeq.Seq seq) implements Plan {
        @Override
        public void commit(SurfaceFold fold) {
            fold.nodes.add(seq);
        }
    }

    private record ReplacePlan(
            int startIdx, int endIdx, SessionSeq.Seq seq, SessionSeq.Seq start, SessionSeq.Seq end)
            implements Plan {
        @Override
        public void commit(SurfaceFold fold) {
            fold.nodes.subList(startIdx, endIdx + 1).clear();
            fold.nodes.add(startIdx, seq);
            fold.replaceGeneration += 1;
            fold.contentGeneration += 1;
        }
    }

    /**
     * Validate the next candidate against the committed surface without changing it.
     *
     * @param event the candidate event, which has not entered the log
     * @param history events already committed before the candidate, in contiguous sequence order
     * @throws IllegalArgumentException when the candidate violates surface metadata, source
     *     attribution, or replacement rules
     */
    public void validateNext(SessionEvent event, List<SessionEvent> history) {
        plan(event, history);
    }

    /**
     * Validate the next candidate and, on success, commit it atomically.
     *
     * @param event the event entering the log
     * @param history events already committed before this one
     * @throws IllegalArgumentException when the candidate is rejected; the fold is unchanged
     */
    public void accept(SessionEvent event, List<SessionEvent> history) {
        plan(event, history).commit(this);
    }

    /** Surface event positions in model-visible order. */
    public List<SessionSeq.Seq> nodes() {
        return List.copyOf(nodes);
    }

    /** Monotonic count of committed positional replacements. */
    public long replaceGeneration() {
        return replaceGeneration;
    }

    /** Monotonic count of committed changes to existing model-visible content. */
    public long contentGeneration() {
        return contentGeneration;
    }

    /** The index of a node in current surface order, or -1 when it is not on the surface. */
    public int indexOf(SessionSeq.Seq seq) {
        return nodes.indexOf(seq);
    }

    /** Whether a position is currently a surface node. */
    public boolean contains(SessionSeq.Seq seq) {
        return nodes.contains(seq);
    }

    /**
     * Replay a complete log through this fold.
     *
     * @param events contiguous events in sequence order
     * @throws IllegalArgumentException when any event violates the surface rules
     */
    public void replay(List<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        nodes.clear();
        replaceGeneration = 0;
        contentGeneration = 0;
        for (int index = 0; index < events.size(); index++) {
            SessionEvent event = events.get(index);
            SessionSeq.Seq expected = SessionSeq.Seq.of(index);
            if (!event.seq().equals(expected)) {
                throw new IllegalArgumentException(
                        "session event seq "
                                + event.seq()
                                + " is not contiguous; expected "
                                + expected);
            }
            accept(event, events.subList(0, index));
        }
    }

    private Plan plan(SessionEvent event, List<SessionEvent> history) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(history, "history");
        if (!SessionEventTypes.isSurfaceEligible(event.type())) {
            if (event.isSurface()) {
                throw new IllegalArgumentException(
                        "session event \""
                                + event.type()
                                + "\" is not surface-eligible and cannot carry a surfaceOp");
            }
            return null;
        }
        SurfaceOp op = event.surfaceOp();
        if (op == null) {
            throw new IllegalArgumentException(
                    "session event \""
                            + event.type()
                            + "\" is surface-eligible and requires a surfaceOp marker");
        }
        if (op instanceof SurfaceOp.Append) {
            return new AppendPlan(event.seq());
        }
        return planReplace(event, (SurfaceOp.Replace) op, history);
    }

    private Plan planReplace(SessionEvent event, SurfaceOp.Replace op, List<SessionEvent> history) {
        NodeIndex range = locate(op);
        if (op.start().compareTo(event.seq()) >= 0 || op.end().compareTo(event.seq()) >= 0) {
            throw new IllegalArgumentException(
                    "surface replace at seq "
                            + event.seq()
                            + ": startSeq and endSeq must reference earlier events");
        }
        requireShadowedSources(event, range.shadowedSeqs());
        requireDeveloperHeader(event, history);
        requireToolResultRewrite(event, range.shadowedSeqs(), history);
        requireSystemHeadRewrite(event, range.startIdx(), range.shadowedSeqs(), history);
        return new ReplacePlan(range.startIdx(), range.endIdx(), event.seq(), op.start(), op.end());
    }

    private NodeIndex locate(SurfaceOp.Replace op) {
        int startIdx = nodes.indexOf(op.start());
        if (startIdx == -1) {
            throw new IllegalArgumentException(
                    "surface replace: start seq " + op.start() + " not found in surface");
        }
        int endIdx = nodes.indexOf(op.end());
        if (endIdx == -1) {
            throw new IllegalArgumentException(
                    "surface replace: end seq " + op.end() + " not found in surface");
        }
        if (startIdx > endIdx) {
            throw new IllegalArgumentException(
                    "surface replace: start seq "
                            + op.start()
                            + " (index "
                            + startIdx
                            + ") is after end seq "
                            + op.end()
                            + " (index "
                            + endIdx
                            + ")");
        }
        return new NodeIndex(startIdx, endIdx, List.copyOf(nodes.subList(startIdx, endIdx + 1)));
    }

    /**
     * A replacement must cite every node it shadows, so a consumer can reconstruct what was hidden
     * without re-deriving the surface.
     */
    private static void requireShadowedSources(
            SessionEvent event, List<SessionSeq.Seq> shadowedSeqs) {
        Set<SessionSeq.Seq> sources = new HashSet<>(event.sourceEventSeqs());
        List<SessionSeq.Seq> missing = new ArrayList<>();
        for (SessionSeq.Seq shadowed : shadowedSeqs) {
            if (!sources.contains(shadowed)) {
                missing.add(shadowed);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "surface replace: sourceEventSeqs must include every shadowed surface node;"
                            + " missing "
                            + join(missing));
        }
    }

    /**
     * Tool additions resolve against an immutable historical header, so a developer message names an
     * earlier {@code request/header} that defines every tool it re-offers.
     */
    private static void requireDeveloperHeader(SessionEvent event, List<SessionEvent> history) {
        if (!(event.data() instanceof SessionEventData.DeveloperMessageData data)) {
            return;
        }
        SessionSeq.Seq headerSeq = data.headerSeq();
        if (headerSeq == null) {
            return;
        }
        if (headerSeq.compareTo(event.seq()) >= 0 || headerSeq.value() >= history.size()) {
            throw new IllegalArgumentException(
                    "developer/message headerSeq must reference an earlier request/header");
        }
        SessionEvent header = history.get((int) headerSeq.value());
        if (!SessionEventTypes.REQUEST_HEADER.equals(header.type())) {
            throw new IllegalArgumentException(
                    "developer/message headerSeq must reference an earlier request/header");
        }
    }

    /**
     * A tool-result replacement may change only content, so a failure identity or model-facing
     * identity cannot be rewritten after the fact.
     */
    private static void requireToolResultRewrite(
            SessionEvent event, List<SessionSeq.Seq> shadowedSeqs, List<SessionEvent> history) {
        if (!(event.data() instanceof SessionEventData.ToolResultData replacement)) {
            return;
        }
        if (shadowedSeqs.size() != 1) {
            throw new IllegalArgumentException(
                    "tool/result surface replacement must rewrite exactly one current node");
        }
        SessionSeq.Seq originalSeq = shadowedSeqs.get(0);
        if (originalSeq.value() >= history.size()) {
            throw new IllegalArgumentException(
                    "tool/result surface replacement must target a current tool/result");
        }
        SessionEvent original = history.get((int) originalSeq.value());
        if (!(original.data() instanceof SessionEventData.ToolResultData shadowed)) {
            throw new IllegalArgumentException(
                    "tool/result surface replacement must target a current tool/result");
        }
        if (!shadowed.callId().equals(replacement.callId())
                || !shadowed.toolName().equals(replacement.toolName())
                || shadowed.isError() != replacement.isError()
                || !Objects.equals(shadowed.error(), replacement.error())
                || !Objects.equals(shadowed.meta(), replacement.meta())) {
            throw new IllegalArgumentException(
                    "tool/result surface replacement may change only content");
        }
    }

    /**
     * Surface node 0 holds the system prompt. Only a system message over exactly that node may
     * rewrite it; later system nodes are ordinary history a compaction range may shadow.
     */
    private void requireSystemHeadRewrite(
            SessionEvent event,
            int startIdx,
            List<SessionSeq.Seq> shadowedSeqs,
            List<SessionEvent> history) {
        if (startIdx != 0) {
            return;
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "surface replace: start seq is not present on an empty surface");
        }
        SessionSeq.Seq headSeq = nodes.get(0);
        if (headSeq.value() >= history.size()) {
            return;
        }
        SessionEvent head = history.get((int) headSeq.value());
        if (!SessionEventTypes.SYSTEM_MESSAGE.equals(head.type())) {
            return;
        }
        if (!SessionEventTypes.SYSTEM_MESSAGE.equals(event.type()) || shadowedSeqs.size() != 1) {
            throw new IllegalArgumentException(
                    "surface replace: node 0 holds the system prompt and may be rewritten only by a"
                            + " system/message over exactly that node");
        }
    }

    private static String join(List<SessionSeq.Seq> values) {
        Set<String> rendered = new TreeSet<>();
        for (SessionSeq.Seq value : values) {
            rendered.add(value.toString());
        }
        return String.join(", ", rendered);
    }
}
