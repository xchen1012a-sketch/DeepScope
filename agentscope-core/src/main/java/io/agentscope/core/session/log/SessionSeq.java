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

/**
 * A position in a session log.
 *
 * <p>Three distinct meanings share one numeric domain and must not be interchanged:
 *
 * <ul>
 *   <li>{@link Seq} identifies an existing event, or an inclusive watermark over existing events.
 *   <li>{@link Offset} identifies a gap, a prefix length, or a read boundary, and may equal the
 *       event count.
 *   <li>{@link Cursor} additionally admits {@code -1}, meaning no event exists yet.
 * </ul>
 *
 * <p>The log length equals the next free sequence number, so {@code seq == log.length} holds for
 * every existing event and is enforced on append.
 */
public final class SessionSeq {

    private SessionSeq() {}

    /** An existing event position, or an inclusive watermark over existing events. */
    public record Seq(long value) implements Comparable<Seq> {

        public Seq {
            requireNonNegative(value, "SessionSeq");
        }

        public static Seq of(long value) {
            return new Seq(value);
        }

        public boolean isBefore(Seq other) {
            return value < other.value;
        }

        public boolean isAfter(Seq other) {
            return value > other.value;
        }

        @Override
        public int compareTo(Seq other) {
            return Long.compare(value, other.value);
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    /** A gap, prefix length, or read boundary that may equal the event count. */
    public record Offset(long value) implements Comparable<Offset> {

        public Offset {
            requireNonNegative(value, "SessionLogOffset");
        }

        public static Offset of(long value) {
            return new Offset(value);
        }

        /** The offset immediately after {@code count} events, which is also their event count. */
        public static Offset afterCount(long count) {
            return new Offset(count);
        }

        public long asCount() {
            return value;
        }

        public Seq asFirstSeq() {
            return new Seq(value);
        }

        @Override
        public int compareTo(Offset other) {
            return Long.compare(value, other.value);
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    /** An inclusive event watermark, or {@code -1} before any event exists. */
    public static final class Cursor {

        /** The cursor value meaning no event exists yet. */
        public static final Cursor NONE = new Cursor(-1);

        private final long value;

        private Cursor(long value) {
            if (value < -1) {
                throw new IllegalArgumentException("SessionSeqCursor must be >= -1, got " + value);
            }
            this.value = value;
        }

        public static Cursor of(long value) {
            return value == -1 ? NONE : new Cursor(value);
        }

        public static Cursor at(Seq seq) {
            return new Cursor(seq.value());
        }

        /** The cursor immediately before {@code offset}, which is the last folded position. */
        public static Cursor beforeOffset(Offset offset) {
            return new Cursor(offset.value() - 1);
        }

        public boolean isPresent() {
            return value >= 0;
        }

        public long value() {
            return value;
        }

        public Seq require() {
            if (value < 0) {
                throw new IllegalStateException("No session event precedes this position");
            }
            return new Seq(value);
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Cursor cursor && cursor.value == value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative, got " + value);
        }
    }
}
