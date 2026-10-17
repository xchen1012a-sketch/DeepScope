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

/**
 * How a message-producing event entered the model-visible surface.
 *
 * <p>Only message-producing event types carry a surface operation; log-only events must not. The
 * model-visible surface is a derived view: replacing an entry shadows an earlier range without
 * deleting it, so the log keeps every original record.
 */
public sealed interface SurfaceOp {

    /** Added to the surface tail. The normal path for user, assistant, and tool messages. */
    Append APPEND = new Append();

    /** Convenience constructor for the tail-append operation. */
    static SurfaceOp append() {
        return APPEND;
    }

    /**
     * Replace an existing surface range with this event's node.
     *
     * @param start first shadowed surface node, inclusive, named by the sequence of the event that
     *     created it
     * @param end last shadowed surface node, inclusive
     */
    static SurfaceOp replace(SessionSeq.Seq start, SessionSeq.Seq end) {
        return new Replace(start, end);
    }

    /** Added to the surface tail. */
    record Append() implements SurfaceOp {}

    /**
     * Shadow the inclusive range {@code start..end} in current surface order and insert this event
     * in its place.
     *
     * <p>Both endpoints must exist as surface nodes when the replacement is planned, must precede
     * the replacing event, and must appear in surface order rather than numeric order. Equal
     * endpoints replace a single node. The replacing event must cite every shadowed node through
     * {@link SessionEvent#sourceEventSeqs()}.
     */
    record Replace(SessionSeq.Seq start, SessionSeq.Seq end) implements SurfaceOp {
        public Replace {
            if (start == null || end == null) {
                throw new IllegalArgumentException("Replace requires both endpoints");
            }
        }

        /** Whether this replacement covers exactly one node. */
        public boolean isSingleNode() {
            return start.equals(end);
        }
    }
}
