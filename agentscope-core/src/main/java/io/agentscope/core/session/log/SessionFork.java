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
import java.util.List;

/**
 * Builds a fork seed: the events a child session starts from when branching off a source.
 *
 * <p>A fork copies an inclusive prefix, marks the inherited cut, and closes only the tail the prefix
 * left open. Closed steps and turns stay exactly as recorded, including a historical missing tool
 * result, because the child did not run them. The source log is never modified.
 *
 * <p>The marker and any closers belong to the child, so {@link Seed#inheritedEventCount()} counts
 * only the copied prefix. That is what lets a reader separate inherited history from work the child
 * actually performed.
 */
public final class SessionFork {

    private SessionFork() {}

    /**
     * One child session's starting events.
     *
     * @param events the copied prefix, then the inherited marker, then any synthetic closers
     * @param inheritedEventCount the exact number of copied events, excluding the marker and closers
     * @param parentSession the source session this child branched from, or null when unknown
     * @param isSeeded always true: a fork always inherits history
     */
    public record Seed(
            List<SessionEvent> events,
            long inheritedEventCount,
            String parentSession,
            boolean isSeeded) {

        public Seed {
            events = List.copyOf(events);
            if (inheritedEventCount < 0 || inheritedEventCount > events.size()) {
                throw new IllegalArgumentException(
                        "inheritedEventCount must be within the seed, got " + inheritedEventCount);
            }
        }
    }

    /**
     * Build a seed from a source prefix ending at the last event.
     *
     * @param source the source session's committed events
     * @return the child seed
     */
    public static Seed buildForkSeed(List<SessionEvent> source) {
        return buildForkSeed(source, null, null);
    }

    /**
     * Build a seed from a source prefix ending at an explicit boundary.
     *
     * @param source the source session's committed events
     * @param boundary the last copied event, inclusive; null copies the whole log
     * @return the child seed
     */
    public static Seed buildForkSeed(List<SessionEvent> source, SessionSeq.Seq boundary) {
        return buildForkSeed(source, boundary, null);
    }

    /**
     * Build a seed from a source prefix ending at an explicit boundary.
     *
     * @param source the source session's committed events
     * @param boundary the last copied event, inclusive; null copies the whole log
     * @param parentSession the source session id, when the caller knows it
     * @return the child seed
     * @throws IllegalArgumentException when the source is empty or the boundary is not in the log
     */
    public static Seed buildForkSeed(
            List<SessionEvent> source, SessionSeq.Seq boundary, String parentSession) {
        if (source == null || source.isEmpty()) {
            if (boundary != null) {
                throw new IllegalArgumentException(
                        "A boundary requires a non-empty source, got " + boundary);
            }
            return new Seed(List.of(), 0, parentSession, true);
        }
        long cut = boundary == null ? source.size() - 1 : boundary.value();
        if (cut < 0 || cut >= source.size()) {
            throw new IllegalArgumentException(
                    "Fork boundary " + cut + " is outside the source log of " + source.size());
        }

        List<SessionEvent> prefix = new ArrayList<>(source.subList(0, (int) cut + 1));
        long inheritedEventCount = prefix.size();

        long time = prefix.get(prefix.size() - 1).time();
        List<SessionEvent> seed = new ArrayList<>(prefix);
        seed.add(
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(inheritedEventCount),
                        time,
                        new SessionRequestData.EndSeed(true)));

        // Close only the tail the prefix left open. The closers reuse the fork cause, so their
        // wording tells the model the parent may have finished the call after the fork point.
        for (SessionEvent closer :
                TurnRepair.openTurnClosers(prefix, TurnRepair.CloseCause.FORKED)) {
            seed.add(
                    SessionEvent.logOnly(
                            SessionSeq.Seq.of(seed.size()), closer.time(), closer.data()));
        }

        return new Seed(seed, inheritedEventCount, parentSession, true);
    }

    /**
     * Fold a restored seed to its inherited cut: the position of the last tagged marker.
     *
     * @param events the restored seed events
     * @return the inherited event count, or zero when the seed carries no tagged marker
     */
    public static long inheritedEventCountOf(List<SessionEvent> events) {
        long cut = 0;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).data() instanceof SessionRequestData.EndSeed marker
                    && marker.inherited()) {
                cut = index;
            }
        }
        return cut;
    }

    /**
     * Whether a position was produced by the child rather than inherited from the fork prefix.
     *
     * @param seq the position to test
     * @param inheritedEventCount the exact inherited prefix length
     * @param size the child's committed event count
     * @return whether the child owns that position
     */
    public static boolean isChildOwned(long seq, long inheritedEventCount, long size) {
        return seq >= inheritedEventCount && seq < size;
    }
}
