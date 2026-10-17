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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The payload of one session event.
 *
 * <p>Two families exist. {@link SurfaceData} covers the event types that produce model-visible
 * messages; the kernel interprets those payloads, so they are modeled as typed records.
 * {@link PayloadData} carries every other event type as an opaque JSON object so an extension can
 * add records without changing the kernel or its storage.
 *
 * <p>Every payload must be losslessly serializable to JSON. Validation happens once at append, on
 * the complete event envelope.
 *
 * <p>The interface is not sealed: an extension declares its own record for a new event type.
 * {@link SurfaceData} is sealed, because the kernel reasons about exactly the message-producing
 * family and adding a member there would silently create a message the fold does not understand.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SessionEventData.PayloadData.class, name = "payload"),
    @JsonSubTypes.Type(value = SessionEventData.SystemMessageData.class, name = "system"),
    @JsonSubTypes.Type(value = SessionEventData.DeveloperMessageData.class, name = "developer"),
    @JsonSubTypes.Type(value = SessionEventData.UserMessageData.class, name = "user"),
    @JsonSubTypes.Type(value = SessionEventData.AssistantMessageData.class, name = "assistant"),
    @JsonSubTypes.Type(value = SessionEventData.ToolResultData.class, name = "tool-result"),
    @JsonSubTypes.Type(value = SessionExecutionData.TurnStart.class, name = "turn-start"),
    @JsonSubTypes.Type(value = SessionExecutionData.TurnEnd.class, name = "turn-end"),
    @JsonSubTypes.Type(value = SessionExecutionData.StepStart.class, name = "step-start"),
    @JsonSubTypes.Type(value = SessionExecutionData.StepEnd.class, name = "step-end"),
    @JsonSubTypes.Type(value = SessionExecutionData.ToolCall.class, name = "tool-call"),
    @JsonSubTypes.Type(value = SessionExecutionData.AssistantAttempt.class, name = "attempt"),
    @JsonSubTypes.Type(value = SessionRequestData.RequestHeader.class, name = "request-header"),
    @JsonSubTypes.Type(value = SessionRequestData.RequestContext.class, name = "request-context"),
    @JsonSubTypes.Type(value = SessionRequestData.EndSeed.class, name = "end-seed"),
    @JsonSubTypes.Type(value = SessionInputQueue.Input.class, name = "input"),
    @JsonSubTypes.Type(value = SessionTitle.TitleData.class, name = "title")
})
public interface SessionEventData {

    /** The event type discriminator this payload belongs to. */
    String type();

    /** A payload the kernel interprets, attached to one of the message-producing event types. */
    sealed interface SurfaceData extends SessionEventData
            permits SystemMessageData,
                    DeveloperMessageData,
                    UserMessageData,
                    AssistantMessageData,
                    ToolResultData {

        /**
         * The message content this event contributes, or an empty list when the node produces no
         * message.
         */
        List<ContentBlock> content();
    }

    /**
     * A payload the kernel stores without interpreting, attached to a log-only event type.
     *
     * <p>The fields map tolerates null values because a recorded JSON object may legitimately hold
     * them — an event that reports no usage or no finish reason says so with an explicit null, and
     * discarding that would turn "reported as absent" into "never reported". The map is wrapped
     * immutably rather than copied by {@code Map.copyOf}, which rejects null values.
     */
    record PayloadData(String type, Map<String, Object> fields) implements SessionEventData {
        public PayloadData {
            Objects.requireNonNull(type, "type");
            if (type.isBlank()) {
                throw new IllegalArgumentException("Event type must not be blank");
            }
            fields =
                    fields == null
                            ? Map.of()
                            : java.util.Collections.unmodifiableMap(
                                    frozenFields(new java.util.LinkedHashMap<>(fields)));
        }

        /**
         * The fields as JSON-native values, with every nested content block stored as its
         * polymorphic JSON object.
         *
         * <p>A {@code Map<String, Object>} erases the declared type of its values, so Jackson writes
         * a nested {@link ContentBlock} without the {@code type} discriminator a reader needs to
         * decode it. Values that are already JSON-native are passed through untouched, so reading a
         * stored payload back never rewrites its numbers, dates, or explicit nulls.
         */
        private static Map<String, Object> frozenFields(
                java.util.LinkedHashMap<String, Object> fields) {
            java.util.LinkedHashMap<String, Object> preserved = new java.util.LinkedHashMap<>();
            boolean native_ = true;
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                Object value = blockPreserving(entry.getValue());
                preserved.put(entry.getKey(), value);
                native_ &= jsonNative(value);
            }
            if (native_) {
                return preserved;
            }
            Object parsed =
                    JsonUtils.getJsonCodec()
                            .fromJson(JsonUtils.getJsonCodec().toJson(preserved), Object.class);
            Map<String, Object> frozen = new java.util.LinkedHashMap<>();
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((key, value) -> frozen.put(String.valueOf(key), value));
            }
            return frozen;
        }

        /**
         * One value with its content blocks already written as their own JSON object.
         *
         * <p>A block is serialized on its own, as the root value, which is the only position where
         * Jackson applies the message model's polymorphic serializer and writes its discriminator.
         */
        private static Object blockPreserving(Object value) {
            if (value instanceof ContentBlock block) {
                return JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(block), Map.class);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(String.valueOf(key), blockPreserving(item)));
                return copy;
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(blockPreserving(item));
                }
                return copy;
            }
            return value;
        }
    }

    /**
     * Whether a value is already representable as JSON, so wrapping it costs nothing and a nested
     * {@link ContentBlock} keeps its own representation.
     */
    private static boolean jsonNative(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (!jsonNative(item)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!jsonNative(item)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** The rendered system prompt occupying one surface node. */
    record SystemMessageData(List<ContentBlock> content) implements SurfaceData {
        public SystemMessageData {
            content = content == null ? List.of() : List.copyOf(content);
        }

        @Override
        public String type() {
            return SessionEventTypes.SYSTEM_MESSAGE;
        }
    }

    /** An incremental session change admitted at a turn and step. */
    record DeveloperMessageData(
            long turn, long step, List<ContentBlock> content, SessionSeq.Seq headerSeq)
            implements SurfaceData {
        public DeveloperMessageData {
            content = content == null ? List.of() : List.copyOf(content);
        }

        @Override
        public String type() {
            return SessionEventTypes.DEVELOPER_MESSAGE;
        }
    }

    /**
     * A user-role message: a direct human prompt, an injected context notice, or a continuation
     * round. The source identifies the producer so consumers can tell them apart.
     *
     * @param messageId stable message identity, retained across projection
     * @param content message content, projected verbatim
     * @param source producer attribution; {@code null} means a direct human prompt
     */
    record UserMessageData(String messageId, List<ContentBlock> content, MessageSource source)
            implements SurfaceData {
        public UserMessageData {
            Objects.requireNonNull(messageId, "messageId");
            content = content == null ? List.of() : List.copyOf(content);
        }

        /** A direct human prompt. */
        public static UserMessageData of(String messageId, List<ContentBlock> content) {
            return new UserMessageData(messageId, content, null);
        }

        @Override
        public String type() {
            return SessionEventTypes.USER_MESSAGE;
        }
    }

    /**
     * The assembled assistant message for one model call.
     *
     * <p>Content includes reasoning blocks as first-class content, so a settled message carries its
     * own thinking and a reader can rebuild a reasoning row without the live stream. The embedded
     * {@code stream} is replay and diagnostic evidence, not a second message.
     */
    record AssistantMessageData(
            long turn,
            long step,
            String messageId,
            List<ContentBlock> content,
            List<AssistantStreamRecord> stream,
            Usage usage,
            boolean interrupted,
            String agentName)
            implements SurfaceData {
        public AssistantMessageData {
            Objects.requireNonNull(messageId, "messageId");
            content = content == null ? List.of() : List.copyOf(content);
            stream = stream == null ? List.of() : List.copyOf(stream);
            agentName = agentName == null || agentName.isBlank() ? null : agentName;
        }

        /**
         * An assistant message recorded before sender attribution was part of the payload.
         * Readers treat a missing name as unknown, not as this agent.
         */
        public AssistantMessageData(
                long turn,
                long step,
                String messageId,
                List<ContentBlock> content,
                List<AssistantStreamRecord> stream,
                Usage usage,
                boolean interrupted) {
            this(turn, step, messageId, content, stream, usage, interrupted, null);
        }

        @Override
        public String type() {
            return SessionEventTypes.ASSISTANT_MESSAGE;
        }
    }

    /**
     * A completed tool call's model-facing result.
     *
     * @param callId pairs this result with the model's tool request; unchanged across every phase
     * @param toolName the invoked tool
     * @param content result content
     * @param isError whether the call failed
     * @param error optional structured failure identity, kept outside model content
     * @param meta optional tool-private presentation payload, opaque to the kernel
     */
    record ToolResultData(
            String callId,
            String toolName,
            List<ContentBlock> content,
            boolean isError,
            ToolFailure error,
            Map<String, Object> meta)
            implements SurfaceData {
        public ToolResultData {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            content = content == null ? List.of() : List.copyOf(content);
            meta = meta == null ? null : Map.copyOf(meta);
            if (error != null && !isError) {
                throw new IllegalArgumentException("tool result failure identity requires isError");
            }
        }

        @Override
        public String type() {
            return SessionEventTypes.TOOL_RESULT;
        }
    }

    /** Structured failure identity of a tool call, outside the model-facing content. */
    record ToolFailure(String name, String code, String reason) {
        public ToolFailure {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(code, "code");
        }
    }

    /** Producer attribution of a user-role message. */
    record MessageSource(String kind) {
        public MessageSource {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Token accounting reported by the adapter for one model call. */
    record Usage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            Long reasoningTokens) {
        public Usage {
            if (inputTokens < 0
                    || outputTokens < 0
                    || cacheReadTokens < 0
                    || cacheWriteTokens < 0) {
                throw new IllegalArgumentException("Token counts must be non-negative");
            }
            if (reasoningTokens != null && reasoningTokens < 0) {
                throw new IllegalArgumentException("reasoningTokens must be non-negative");
            }
        }
    }
}
