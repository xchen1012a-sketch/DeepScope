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
import java.util.Map;
import java.util.Objects;

/**
 * Lossless compact representation of one model stream, embedded in a durable assistant event.
 *
 * <p>A run packs consecutive deltas of one kind into parallel arrays so a long stream costs one
 * record per run rather than one per delta: {@code time0} is the first delta's epoch millisecond,
 * {@code dt} holds each later delta's offset from the previous one, and the payload array holds one
 * entry per delta. Delta run membership is never merged across kinds, so replay recovers the exact
 * original delta boundaries and timing.
 *
 * <p>A delta type that is not packable appears as {@link Raw}, whose chunk stays opaque to the
 * kernel.
 */
public sealed interface AssistantStreamRecord {

    /** Index of the content block the run belongs to, as reported by the provider. */
    int index();

    /** A run of text deltas. */
    record TextChunks(long time0, int index, List<Long> dt, List<String> texts)
            implements AssistantStreamRecord {
        public TextChunks {
            Objects.requireNonNull(dt, "dt");
            Objects.requireNonNull(texts, "texts");
            dt = List.copyOf(dt);
            texts = List.copyOf(texts);
            requireAligned(dt, texts.size(), "text-chunks");
        }
    }

    /** A run of reasoning (thinking) deltas. */
    record ReasoningChunks(long time0, int index, List<Long> dt, List<String> texts)
            implements AssistantStreamRecord {
        public ReasoningChunks {
            Objects.requireNonNull(dt, "dt");
            Objects.requireNonNull(texts, "texts");
            dt = List.copyOf(dt);
            texts = List.copyOf(texts);
            requireAligned(dt, texts.size(), "reasoning-chunks");
        }
    }

    /** A run of tool-call argument deltas for one call identity. */
    record ToolCallChunks(
            long time0, int index, List<Long> dt, String callId, String name, List<String> args)
            implements AssistantStreamRecord {
        public ToolCallChunks {
            Objects.requireNonNull(dt, "dt");
            Objects.requireNonNull(args, "args");
            Objects.requireNonNull(callId, "callId");
            dt = List.copyOf(dt);
            args = List.copyOf(args);
            requireAligned(dt, args.size(), "tool-call-chunks");
        }
    }

    /** One chunk the accumulator never packs, such as a phase boundary or a finish reason. */
    record Raw(long time, Map<String, Object> chunk) implements AssistantStreamRecord {
        public Raw {
            Objects.requireNonNull(chunk, "chunk");
            chunk = Map.copyOf(chunk);
        }

        @Override
        public int index() {
            Object index = chunk.get("index");
            return index instanceof Number number ? number.intValue() : -1;
        }
    }

    private static void requireAligned(List<Long> dt, int payloadSize, String kind) {
        if (dt.size() != payloadSize - 1) {
            throw new IllegalArgumentException(
                    kind + " requires one delta offset per payload entry after the first");
        }
    }
}
