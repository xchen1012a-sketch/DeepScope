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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless JSON encoding of one session event.
 *
 * <p>The stored form keeps the event envelope and its payload in one object, so a record, its
 * position, and its surface placement are read back together and cannot drift apart. Content blocks
 * use the message model's own polymorphic representation rather than a parallel one.
 */
final class SessionEventCodec {

    /** Stored key holding a content block's discriminator. */
    private static final String BLOCK_TYPE = "blockType";

    /** Stored key holding a content block's own fields. */
    private static final String BLOCK_BODY = "block";

    private SessionEventCodec() {}

    /**
     * Encode one event as a JSON-compatible map.
     *
     * @param event the event to encode
     * @param mapper the mapper that handles content blocks
     * @return the stored representation
     */
    static Map<String, Object> encode(SessionEvent event, ObjectMapper mapper) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("seq", event.seq().value());
        record.put("time", event.time());
        record.put("type", event.type());
        record.put("data", encodeData(event.data(), mapper));
        if (event.surfaceOp() instanceof SurfaceOp.Append) {
            record.put("surfaceOp", "append");
        } else if (event.surfaceOp() instanceof SurfaceOp.Replace replace) {
            record.put(
                    "surfaceOp",
                    Map.of(
                            "op", "replace",
                            "startSeq", replace.start().value(),
                            "endSeq", replace.end().value()));
        }
        if (!event.sourceEventSeqs().isEmpty()) {
            record.put(
                    "sourceEventSeqs",
                    event.sourceEventSeqs().stream().map(SessionSeq.Seq::value).toList());
        }
        if (event.ignorable()) {
            record.put("ignorable", true);
        }
        if (event.eventId() != null) {
            record.put("eventId", event.eventId());
        }
        if (event.executionRunId() != null) {
            record.put("executionRunId", event.executionRunId());
        }
        if (event.turnId() != null) {
            record.put("turnId", event.turnId());
        }
        return record;
    }

    /**
     * Decode one stored record.
     *
     * @param record the stored map
     * @param mapper the mapper that handles content blocks
     * @param expectedSeq the position the record must occupy
     * @return the restored event
     */
    static SessionEvent decode(Map<?, ?> record, ObjectMapper mapper, int expectedSeq) {
        long seq = number(record.get("seq"), expectedSeq);
        if (seq != expectedSeq) {
            throw new LocalSessionStore.SessionCorruptionException(
                    "Stored session event " + seq + " is out of order; expected " + expectedSeq);
        }
        long time = number(record.get("time"), 0L);
        String type = String.valueOf(record.get("type"));
        SessionEventData data = decodeData(type, asMap(record.get("data")), mapper);
        SurfaceOp surfaceOp = decodeSurfaceOp(record.get("surfaceOp"));
        List<SessionSeq.Seq> sources = decodeSources(record.get("sourceEventSeqs"));
        boolean ignorable = Boolean.TRUE.equals(record.get("ignorable"));
        SessionEvent restored =
                SessionEvent.restore(
                        SessionSeq.Seq.of(seq), time, data, surfaceOp, sources, ignorable);
        return restored.withIdentity(
                text(record.get("eventId")),
                text(record.get("executionRunId")),
                text(record.get("turnId")));
    }

    /**
     * Content blocks keyed by their discriminator.
     *
     * <p>The message model's own JSON serialization omits the block discriminator, so a stored event
     * carries it explicitly. Without it a reader could not tell a thinking block from a text block,
     * and derived history would silently lose reasoning.
     */
    private static List<Map<String, Object>> encodeBlocks(
            List<ContentBlock> blocks, ObjectMapper mapper) {
        List<Map<String, Object>> encoded = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(BLOCK_TYPE, blockTypeOf(block));
            entry.put(BLOCK_BODY, mapper.valueToTree(block));
            encoded.add(entry);
        }
        return encoded;
    }

    private static List<ContentBlock> decodeBlocks(Object raw, ObjectMapper mapper) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ContentBlock> blocks = new ArrayList<>(list.size());
        for (Object entry : list) {
            Map<String, Object> record = asMap(entry);
            String type = String.valueOf(record.get(BLOCK_TYPE));
            Class<? extends ContentBlock> subtype = blockClassOf(type);
            blocks.add(mapper.convertValue(record.get(BLOCK_BODY), subtype));
        }
        return blocks;
    }

    private static String blockTypeOf(ContentBlock block) {
        if (block instanceof io.agentscope.core.message.TextBlock) {
            return "text";
        }
        if (block instanceof io.agentscope.core.message.ThinkingBlock) {
            return "thinking";
        }
        if (block instanceof io.agentscope.core.message.ImageBlock) {
            return "image";
        }
        if (block instanceof io.agentscope.core.message.AudioBlock) {
            return "audio";
        }
        if (block instanceof io.agentscope.core.message.VideoBlock) {
            return "video";
        }
        if (block instanceof io.agentscope.core.message.ToolUseBlock) {
            return "tool_use";
        }
        if (block instanceof io.agentscope.core.message.ToolResultBlock) {
            return "tool_result";
        }
        if (block instanceof io.agentscope.core.message.HintBlock) {
            return "hint";
        }
        if (block instanceof io.agentscope.core.message.DataBlock) {
            return "data";
        }
        throw new IllegalArgumentException(
                "No stored discriminator for content block " + block.getClass().getName());
    }

    private static Class<? extends ContentBlock> blockClassOf(String type) {
        switch (type) {
            case "text":
                return io.agentscope.core.message.TextBlock.class;
            case "thinking":
                return io.agentscope.core.message.ThinkingBlock.class;
            case "image":
                return io.agentscope.core.message.ImageBlock.class;
            case "audio":
                return io.agentscope.core.message.AudioBlock.class;
            case "video":
                return io.agentscope.core.message.VideoBlock.class;
            case "tool_use":
                return io.agentscope.core.message.ToolUseBlock.class;
            case "tool_result":
                return io.agentscope.core.message.ToolResultBlock.class;
            case "hint":
                return io.agentscope.core.message.HintBlock.class;
            case "data":
                return io.agentscope.core.message.DataBlock.class;
            default:
                throw new LocalSessionStore.SessionCorruptionException(
                        "Stored event has an unknown content block type: " + type);
        }
    }

    /**
     * Stream records keyed by their kind.
     *
     * <p>A packed run and a raw chunk are different records with different fields, so the stored form
     * names the kind. Without it a reader would have to guess from field presence.
     */
    private static List<Map<String, Object>> encodeStream(List<AssistantStreamRecord> records) {
        List<Map<String, Object>> encoded = new ArrayList<>(records.size());
        for (AssistantStreamRecord record : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (record instanceof AssistantStreamRecord.TextChunks run) {
                entry.put("type", "text-chunks");
                entry.put("time0", run.time0());
                entry.put("index", run.index());
                entry.put("dt", run.dt());
                entry.put("texts", run.texts());
            } else if (record instanceof AssistantStreamRecord.ReasoningChunks run) {
                entry.put("type", "reasoning-chunks");
                entry.put("time0", run.time0());
                entry.put("index", run.index());
                entry.put("dt", run.dt());
                entry.put("texts", run.texts());
            } else if (record instanceof AssistantStreamRecord.ToolCallChunks run) {
                entry.put("type", "tool-call-chunks");
                entry.put("time0", run.time0());
                entry.put("index", run.index());
                entry.put("dt", run.dt());
                entry.put("callId", run.callId());
                if (run.name() != null) {
                    entry.put("name", run.name());
                }
                entry.put("args", run.args());
            } else if (record instanceof AssistantStreamRecord.Raw raw) {
                entry.put("type", "chunk");
                entry.put("time", raw.time());
                entry.put("chunk", raw.chunk());
            } else {
                throw new IllegalArgumentException(
                        "No stored form for stream record " + record.getClass().getName());
            }
            encoded.add(entry);
        }
        return encoded;
    }

    private static Map<String, Object> encodeUsage(SessionEventData.Usage usage) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("inputTokens", usage.inputTokens());
        fields.put("outputTokens", usage.outputTokens());
        fields.put("cacheReadTokens", usage.cacheReadTokens());
        fields.put("cacheWriteTokens", usage.cacheWriteTokens());
        if (usage.reasoningTokens() != null) {
            fields.put("reasoningTokens", usage.reasoningTokens());
        }
        return fields;
    }

    private static Map<String, Object> encodeFailure(SessionEventData.ToolFailure failure) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", failure.name());
        fields.put("code", failure.code());
        if (failure.reason() != null) {
            fields.put("reason", failure.reason());
        }
        return fields;
    }

    private static Object encodeData(SessionEventData data, ObjectMapper mapper) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (data instanceof SessionEventData.SystemMessageData system) {
            fields.put("content", encodeBlocks(system.content(), mapper));
        } else if (data instanceof SessionEventData.DeveloperMessageData developer) {
            fields.put("turn", developer.turn());
            fields.put("step", developer.step());
            fields.put("content", encodeBlocks(developer.content(), mapper));
            if (developer.headerSeq() != null) {
                fields.put("headerSeq", developer.headerSeq().value());
            }
        } else if (data instanceof SessionEventData.UserMessageData user) {
            fields.put("messageId", user.messageId());
            fields.put("content", encodeBlocks(user.content(), mapper));
            if (user.source() != null) {
                fields.put("source", user.source().kind());
            }
        } else if (data instanceof SessionEventData.AssistantMessageData assistant) {
            fields.put("turn", assistant.turn());
            fields.put("step", assistant.step());
            fields.put("messageId", assistant.messageId());
            fields.put("content", encodeBlocks(assistant.content(), mapper));
            fields.put("stream", encodeStream(assistant.stream()));
            if (assistant.usage() != null) {
                fields.put("usage", encodeUsage(assistant.usage()));
            }
            if (assistant.interrupted()) {
                fields.put("interrupted", true);
            }
            if (assistant.agentName() != null) {
                fields.put("agentName", assistant.agentName());
            }
        } else if (data instanceof SessionEventData.ToolResultData result) {
            fields.put("callId", result.callId());
            fields.put("toolName", result.toolName());
            fields.put("content", encodeBlocks(result.content(), mapper));
            fields.put("isError", result.isError());
            if (result.error() != null) {
                fields.put("failure", encodeFailure(result.error()));
            }
            if (result.meta() != null) {
                fields.put("meta", result.meta());
            }
        } else if (data instanceof SessionExecutionData.TurnStart start) {
            fields.put("turn", start.turn());
        } else if (data instanceof SessionExecutionData.TurnEnd end) {
            fields.put("turn", end.turn());
            fields.put("reason", end.reason().name());
        } else if (data instanceof SessionExecutionData.StepStart start) {
            fields.put("turn", start.turn());
            fields.put("step", start.step());
        } else if (data instanceof SessionExecutionData.StepEnd end) {
            fields.put("turn", end.turn());
            fields.put("step", end.step());
        } else if (data instanceof SessionExecutionData.ToolCall call) {
            fields.put("turn", call.turn());
            fields.put("step", call.step());
            fields.put("callId", call.callId());
            fields.put("toolName", call.toolName());
            fields.put("arguments", call.arguments());
        } else if (data instanceof SessionExecutionData.AssistantAttempt attempt) {
            fields.put("turn", attempt.turn());
            fields.put("step", attempt.step());
            fields.put("stream", encodeStream(attempt.stream()));
        } else if (data instanceof SessionRequestData.RequestHeader header) {
            fields.put("reason", header.reason().name());
            if (header.startsSeries()) {
                fields.put("startsSeries", true);
            }
            fields.put("toolNames", header.toolNames());
            fields.put("config", header.config());
            fields.put("adapterDefaults", header.adapterDefaults());
        } else if (data instanceof SessionRequestData.RequestContext context) {
            fields.put("provider", context.provider());
            fields.put("model", context.model());
            if (context.contextWindow() != null) {
                fields.put("contextWindow", context.contextWindow());
            }
        } else if (data instanceof SessionRequestData.EndSeed seed) {
            fields.put("inherited", seed.inherited());
        } else if (data instanceof SessionInputQueue.Input input) {
            fields.put("requestId", input.requestId());
            if (input.turnId() != null) {
                fields.put("turnId", input.turnId());
            }
            fields.put("startsTurn", input.startsTurn());
            fields.put(
                    "messages",
                    input.messages().stream()
                            .map(message -> mapper.convertValue(message, Map.class))
                            .toList());
        } else if (data instanceof SessionTitle.TitleData title) {
            fields.put("title", title.title());
            fields.put("source", title.source().name());
        } else if (data instanceof SessionEventData.PayloadData payload) {
            fields.put("fields", payload.fields());
        } else {
            throw new IllegalArgumentException(
                    "No stored representation for event payload " + data.getClass().getName());
        }
        return fields;
    }

    private static SessionEventData decodeData(
            String type, Map<String, Object> fields, ObjectMapper mapper) {
        switch (type) {
            case SessionEventTypes.SYSTEM_MESSAGE:
                return new SessionEventData.SystemMessageData(content(fields, mapper));
            case SessionEventTypes.DEVELOPER_MESSAGE:
                return new SessionEventData.DeveloperMessageData(
                        number(fields.get("turn"), 0L),
                        number(fields.get("step"), 0L),
                        content(fields, mapper),
                        fields.containsKey("headerSeq")
                                ? SessionSeq.Seq.of(number(fields.get("headerSeq"), 0L))
                                : null);
            case SessionEventTypes.USER_MESSAGE:
                return new SessionEventData.UserMessageData(
                        String.valueOf(fields.get("messageId")),
                        content(fields, mapper),
                        fields.get("source") == null
                                ? null
                                : new SessionEventData.MessageSource(
                                        String.valueOf(fields.get("source"))));
            case SessionEventTypes.ASSISTANT_MESSAGE:
                return new SessionEventData.AssistantMessageData(
                        number(fields.get("turn"), 0L),
                        number(fields.get("step"), 0L),
                        String.valueOf(fields.get("messageId")),
                        content(fields, mapper),
                        stream(fields, mapper),
                        usage(fields),
                        Boolean.TRUE.equals(fields.get("interrupted")),
                        fields.get("agentName") == null
                                ? null
                                : String.valueOf(fields.get("agentName")));
            case SessionEventTypes.TOOL_RESULT:
                return new SessionEventData.ToolResultData(
                        String.valueOf(fields.get("callId")),
                        String.valueOf(fields.get("toolName")),
                        content(fields, mapper),
                        Boolean.TRUE.equals(fields.get("isError")),
                        failure(fields),
                        fields.get("meta") == null ? null : asMap(fields.get("meta")));
            case SessionEventTypes.TURN_START:
                return new SessionExecutionData.TurnStart(number(fields.get("turn"), 0L));
            case SessionEventTypes.TURN_RESUMED:
                return new SessionExecutionData.TurnStart(number(fields.get("turn"), 0L), true);
            case SessionEventTypes.TURN_END:
                return new SessionExecutionData.TurnEnd(
                        number(fields.get("turn"), 0L),
                        SessionExecutionData.TurnEndReason.valueOf(
                                String.valueOf(fields.get("reason"))));
            case SessionEventTypes.STEP_START:
                return new SessionExecutionData.StepStart(
                        number(fields.get("turn"), 0L), number(fields.get("step"), 0L));
            case SessionEventTypes.STEP_END:
                return new SessionExecutionData.StepEnd(
                        number(fields.get("turn"), 0L), number(fields.get("step"), 0L));
            case SessionEventTypes.TOOL_CALL:
                return new SessionExecutionData.ToolCall(
                        number(fields.get("turn"), 0L),
                        number(fields.get("step"), 0L),
                        String.valueOf(fields.get("callId")),
                        String.valueOf(fields.get("toolName")),
                        String.valueOf(fields.get("arguments")));
            case SessionEventTypes.ASSISTANT_ATTEMPT:
                return new SessionExecutionData.AssistantAttempt(
                        number(fields.get("turn"), 0L),
                        number(fields.get("step"), 0L),
                        stream(fields, mapper));
            case SessionEventTypes.REQUEST_HEADER:
                return new SessionRequestData.RequestHeader(
                        requestHeaderReason(String.valueOf(fields.get("reason"))),
                        Boolean.TRUE.equals(fields.get("startsSeries")),
                        strings(fields.get("toolNames")),
                        fields.get("config") == null ? Map.of() : asMap(fields.get("config")),
                        fields.get("adapterDefaults") == null
                                ? Map.of()
                                : asMap(fields.get("adapterDefaults")));
            case SessionEventTypes.REQUEST_CONTEXT:
                return new SessionRequestData.RequestContext(
                        String.valueOf(fields.get("provider")),
                        String.valueOf(fields.get("model")),
                        fields.get("contextWindow") == null
                                ? null
                                : number(fields.get("contextWindow"), 0L));
            case SessionEventTypes.END_SEED:
                return new SessionRequestData.EndSeed(Boolean.TRUE.equals(fields.get("inherited")));
            case SessionEventTypes.INPUT_SUBMITTED:
            case SessionEventTypes.INPUT_STEERED:
            case SessionEventTypes.INPUT_INJECTED:
            case "resume":
            case "respond":
                return inputRecord(type, fields, mapper);
            case SessionTitle.TITLE_EVENT_TYPE:
                return new SessionTitle.TitleData(
                        String.valueOf(fields.get("title")),
                        SessionTitle.Source.valueOf(String.valueOf(fields.get("source"))));
            default:
                return new SessionEventData.PayloadData(
                        type,
                        fields.get("fields") == null ? Map.of() : asMap(fields.get("fields")));
        }
    }

    private static SessionInputQueue.Input inputRecord(
            String type, Map<String, Object> fields, ObjectMapper mapper) {
        Object raw = fields.get("messages");
        List<io.agentscope.core.message.Msg> messages = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                messages.add(mapper.convertValue(item, io.agentscope.core.message.Msg.class));
            }
        }
        Object turn = fields.get("turnId");
        return new SessionInputQueue.Input(
                String.valueOf(fields.get("requestId")),
                type,
                turn == null ? null : String.valueOf(turn),
                messages,
                Boolean.TRUE.equals(fields.get("startsTurn")));
    }

    /**
     * Parse a request-header reason written by either naming convention.
     *
     * <p>The recording protocol names reasons in lower case while the kernel's constants are upper
     * case. Both describe the same four values, so the stored form accepts either rather than
     * refusing a header its own writer produced.
     *
     * @param raw the stored reason text
     * @return the matching reason
     * @throws LocalSessionStore.SessionCorruptionException when the text names no known reason
     */
    private static SessionRequestData.RequestHeaderReason requestHeaderReason(String raw) {
        for (SessionRequestData.RequestHeaderReason candidate :
                SessionRequestData.RequestHeaderReason.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new LocalSessionStore.SessionCorruptionException(
                "Stored request header names no known reason: " + raw);
    }

    private static List<ContentBlock> content(Map<String, Object> fields, ObjectMapper mapper) {
        return decodeBlocks(fields.get("content"), mapper);
    }

    private static List<AssistantStreamRecord> stream(
            Map<String, Object> fields, ObjectMapper mapper) {
        Object raw = fields.get("stream");
        if (raw == null) {
            return List.of();
        }
        List<AssistantStreamRecord> records = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            Map<String, Object> record = asMap(entry);
            String kind = String.valueOf(record.get("type"));
            switch (kind) {
                case "text-chunks" ->
                        records.add(
                                new AssistantStreamRecord.TextChunks(
                                        number(record.get("time0"), 0L),
                                        (int) number(record.get("index"), 0L),
                                        longs(record.get("dt")),
                                        strings(record.get("texts"))));
                case "reasoning-chunks" ->
                        records.add(
                                new AssistantStreamRecord.ReasoningChunks(
                                        number(record.get("time0"), 0L),
                                        (int) number(record.get("index"), 0L),
                                        longs(record.get("dt")),
                                        strings(record.get("texts"))));
                case "tool-call-chunks" ->
                        records.add(
                                new AssistantStreamRecord.ToolCallChunks(
                                        number(record.get("time0"), 0L),
                                        (int) number(record.get("index"), 0L),
                                        longs(record.get("dt")),
                                        String.valueOf(record.get("callId")),
                                        record.get("name") == null
                                                ? null
                                                : String.valueOf(record.get("name")),
                                        strings(record.get("args"))));
                default ->
                        records.add(
                                new AssistantStreamRecord.Raw(
                                        number(record.get("time"), 0L),
                                        record.get("chunk") == null
                                                ? Map.of()
                                                : asMap(record.get("chunk"))));
            }
        }
        return records;
    }

    private static SessionEventData.Usage usage(Map<String, Object> fields) {
        Object raw = fields.get("usage");
        if (raw == null) {
            return null;
        }
        Map<String, Object> usage = asMap(raw);
        return new SessionEventData.Usage(
                number(usage.get("inputTokens"), 0L),
                number(usage.get("outputTokens"), 0L),
                number(usage.get("cacheReadTokens"), 0L),
                number(usage.get("cacheWriteTokens"), 0L),
                usage.get("reasoningTokens") == null
                        ? null
                        : number(usage.get("reasoningTokens"), 0L));
    }

    private static SessionEventData.ToolFailure failure(Map<String, Object> fields) {
        Object raw = fields.get("failure");
        if (raw == null) {
            return null;
        }
        Map<String, Object> failure = asMap(raw);
        return new SessionEventData.ToolFailure(
                String.valueOf(failure.get("name")),
                String.valueOf(failure.get("code")),
                failure.get("reason") == null ? null : String.valueOf(failure.get("reason")));
    }

    private static SurfaceOp decodeSurfaceOp(Object raw) {
        if (raw == null) {
            return null;
        }
        if ("append".equals(raw)) {
            return SurfaceOp.append();
        }
        Map<String, Object> replace = asMap(raw);
        return SurfaceOp.replace(
                SessionSeq.Seq.of(number(replace.get("startSeq"), 0L)),
                SessionSeq.Seq.of(number(replace.get("endSeq"), 0L)));
    }

    private static List<SessionSeq.Seq> decodeSources(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<SessionSeq.Seq> sources = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            sources.add(SessionSeq.Seq.of(number(entry, 0L)));
        }
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw == null ? Map.of() : (Map<String, Object>) raw;
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.isBlank() ? null : value;
    }

    private static long number(Object raw, long fallback) {
        return raw instanceof Number number ? number.longValue() : fallback;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            values.add(String.valueOf(entry));
        }
        return values;
    }

    private static List<Long> longs(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            values.add(number(entry, 0L));
        }
        return values;
    }
}
