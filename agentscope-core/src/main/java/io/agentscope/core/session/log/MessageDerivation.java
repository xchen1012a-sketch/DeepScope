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

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Projects one session event into the model-visible message it derives to.
 *
 * <p>This is the single rule the whole derived history obeys, exposed so an offline reconstructor
 * projecting a log prefix cannot disagree with the live fold's cache.
 *
 * <p>Only message-producing events derive history. Lifecycle markers, settled attempts, request
 * envelopes, and extension records are replay and diagnostic data and project to nothing. A
 * message-producing node with empty content also projects to nothing: an empty assistant event still
 * hosts its usage and stream, but a content-less turn must not enter the provider transcript.
 */
public final class MessageDerivation {

    private MessageDerivation() {}

    /**
     * Derive the model-visible message for one event.
     *
     * @param event the event to project
     * @return the derived message, or null when the event produces none
     */
    /**
     * Model history for one log. A later record of the same message id replaces the earlier one, so
     * a turn output does not appear as a second copy of the assistant reply.
     *
     * @param log the kernel log
     * @return the messages a rebuilt agent should send
     */
    public static List<Msg> history(SessionLog log) {
        var order = new java.util.LinkedHashMap<String, Msg>();
        Set<String> answeredToolCalls = new HashSet<>();
        int anonymous = 0;
        for (Msg message : log.deriveMessages()) {
            for (ToolUseBlock use : message.getContentBlocks(ToolUseBlock.class)) {
                if (use.getId() != null) {
                    answeredToolCalls.remove(use.getId());
                }
            }
            List<ToolResultBlock> results = message.getContentBlocks(ToolResultBlock.class);
            if (!results.isEmpty()) {
                boolean allAlreadyAnswered =
                        results.stream()
                                .map(ToolResultBlock::getId)
                                .allMatch(id -> id != null && answeredToolCalls.contains(id));
                if (allAlreadyAnswered) {
                    continue;
                }
                for (ToolResultBlock result : results) {
                    if (result.getId() != null) {
                        answeredToolCalls.add(result.getId());
                    }
                }
            }
            String id = message.getId();
            if (id != null) {
                order.remove(id);
                order.put(id, message);
            } else {
                order.put("anon-" + anonymous++, message);
            }
        }
        applyCheckpointCompactionCoverage(log, order);
        return List.copyOf(order.values());
    }

    /**
     * Heals legacy session logs where a compaction summary was committed with {@code SurfaceOp.APPEND}
     * instead of {@code SurfaceOp.Replace}, ensuring covered prefix messages are pruned and the
     * summary sits at the head of the conversation.
     */
    private static void applyCheckpointCompactionCoverage(
            SessionLog log, java.util.LinkedHashMap<String, Msg> order) {
        if (order.isEmpty()) {
            return;
        }
        String checkpointJson = null;
        for (SessionEvent event : log.events()) {
            if (event.data() instanceof SessionEventData.PayloadData payload
                    && ("state/checkpoint".equals(payload.type())
                            || "state/snapshot".equals(payload.type()))
                    && payload.fields().get("stateJson") instanceof String json
                    && !json.isBlank()) {
                checkpointJson = json;
            }
        }
        if (checkpointJson == null) {
            return;
        }
        io.agentscope.core.state.AgentState checkpoint;
        try {
            checkpoint = io.agentscope.core.state.AgentState.fromJsonString(checkpointJson);
        } catch (RuntimeException ignored) {
            return;
        }
        if (checkpoint == null || checkpoint.getContext().isEmpty()) {
            return;
        }
        for (Msg stateMsg : checkpoint.getContext()) {
            Object rawCovered =
                    stateMsg.getMetadata() == null
                            ? null
                            : stateMsg.getMetadata().get("context.covered_messages");
            if (!(rawCovered instanceof java.util.Collection<?> coveredList)
                    || coveredList.isEmpty()
                    || stateMsg.getId() == null
                    || !order.containsKey(stateMsg.getId())) {
                continue;
            }
            boolean removedAny = false;
            for (Object item : coveredList) {
                if (item instanceof String coveredId && !coveredId.equals(stateMsg.getId())) {
                    removedAny |= order.remove(coveredId) != null;
                }
            }
            Msg current = order.remove(stateMsg.getId());
            if (current == null) {
                continue;
            }
            Msg enriched = current.withMetadata(stateMsg.getMetadata());
            if (removedAny) {
                var reordered = new java.util.LinkedHashMap<String, Msg>();
                boolean inserted = false;
                for (var entry : order.entrySet()) {
                    if (!inserted
                            && entry.getValue().getRole()
                                    != io.agentscope.core.message.MsgRole.SYSTEM) {
                        reordered.put(stateMsg.getId(), enriched);
                        inserted = true;
                    }
                    reordered.put(entry.getKey(), entry.getValue());
                }
                if (!inserted) {
                    reordered.put(stateMsg.getId(), enriched);
                }
                order.clear();
                order.putAll(reordered);
            } else {
                // Keep exact surface order from SurfaceOp.Replace while preserving covered metadata
                var reordered = new java.util.LinkedHashMap<String, Msg>();
                boolean inserted = false;
                for (Msg derived : log.deriveMessages()) {
                    if (stateMsg.getId().equals(derived.getId()) && !inserted) {
                        reordered.put(stateMsg.getId(), enriched);
                        inserted = true;
                    } else if (derived.getId() != null && order.containsKey(derived.getId())) {
                        reordered.put(derived.getId(), order.get(derived.getId()));
                    }
                }
                for (var entry : order.entrySet()) {
                    reordered.putIfAbsent(entry.getKey(), entry.getValue());
                }
                order.clear();
                order.putAll(reordered);
            }
        }
    }

    public static Msg derive(SessionEvent event) {
        SessionEventData data = event.data();
        if (data instanceof SessionEventData.UserMessageData user) {
            var builder =
                    UserMessage.builder().id(user.messageId()).content(copyContent(user.content()));
            if ((user.source() != null && "__compaction_summary__".equals(user.source().kind()))
                    || user.messageId().startsWith("__compaction_summary__:")) {
                builder.name("__compaction_summary__");
            }
            return builder.build();
        }
        if (data instanceof SessionEventData.SystemMessageData system) {
            if (system.content().isEmpty()) {
                return null;
            }
            return SystemMessage.builder()
                    .id(systemIdentity(event))
                    .content(copyContent(system.content()))
                    .build();
        }
        if (data instanceof SessionEventData.DeveloperMessageData developer) {
            if (developer.content().isEmpty()) {
                return null;
            }
            String id = developerIdentity(event);
            return withSessionMessageId(assistantMessage(id, copyContent(developer.content())), id);
        }
        if (data instanceof SessionEventData.AssistantMessageData assistant) {
            if (assistant.content().isEmpty()) {
                return null;
            }
            var message =
                    AssistantMessage.builder()
                            .id(assistant.messageId())
                            .content(copyContent(assistant.content()));
            if (assistant.agentName() != null) {
                message.name(assistant.agentName());
            }
            return withSessionMessageId(message.build(), assistant.messageId());
        }
        if (data instanceof SessionEventData.ToolResultData result) {
            return toolResultMessage(result);
        }
        return null;
    }

    private static Msg toolResultMessage(SessionEventData.ToolResultData result) {
        List<ToolResultBlock> blocks = new ArrayList<>(result.content().size());
        for (ContentBlock block : result.content()) {
            if (block instanceof ToolResultBlock toolResult) {
                blocks.add(toolResult);
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(
                    ToolResultBlock.builder()
                            .id(result.callId())
                            .name(result.toolName())
                            .output(List.of())
                            .build());
        }
        String messageId = toolResultMessageId(result);
        ToolResultMessage.Builder builder = ToolResultMessage.builder().results(blocks);
        if (messageId != null && !messageId.isBlank()) {
            builder.id(messageId);
        }
        return withSessionMessageId(builder.build(), result.callId());
    }

    private static String toolResultMessageId(SessionEventData.ToolResultData result) {
        if (result.meta() != null
                && result.meta().get("messageId") instanceof String metaId
                && !metaId.isBlank()) {
            return metaId;
        }
        return result.callId();
    }

    private static Msg withSessionMessageId(Msg message, String messageId) {
        return message.withMetadataEntry(MessageMetadataKeys.SESSION_MESSAGE_ID, messageId);
    }

    private static Msg assistantMessage(String id, List<ContentBlock> content) {
        return AssistantMessage.builder().id(id).content(content).build();
    }

    private static String systemIdentity(SessionEvent event) {
        return "system-" + event.seq();
    }

    private static String developerIdentity(SessionEvent event) {
        return "developer-" + event.seq();
    }

    private static List<ContentBlock> copyContent(List<ContentBlock> content) {
        return List.copyOf(content);
    }
}
