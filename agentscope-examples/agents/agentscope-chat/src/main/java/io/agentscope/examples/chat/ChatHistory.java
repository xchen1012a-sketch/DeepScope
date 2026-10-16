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
package io.agentscope.examples.chat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.session.log.MessageDerivation;
import io.agentscope.core.session.log.SessionEvent;
import io.agentscope.core.session.log.SessionEventData;
import io.agentscope.core.session.log.SessionExecutionData;
import io.agentscope.core.session.log.SessionLog;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.session.AgentSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A UI snapshot and its cursor are built from exactly the same committed prefix. */
final class ChatHistory {
    record Turn(String turnId, String runId, String status) {}

    record Frame(
            long seq,
            String eventId,
            String type,
            String turnId,
            String runId,
            Map<String, Object> data) {}

    record Snapshot(
            String sessionId,
            String cursor,
            List<Msg> messages,
            List<ChatItems.Item> items,
            List<Turn> turns,
            List<Frame> pending,
            List<Frame> trace,
            long checkpointSeq,
            int workingMessages,
            Set<String> uncertainTools,
            String executionError,
            List<AgentSession.Task> tasks) {}

    static Snapshot snapshot(String sessionId, SessionLog log, String activeRun, String error) {
        return snapshot(sessionId, log, activeRun, error, List.of());
    }

    static Snapshot snapshot(
            String sessionId,
            SessionLog log,
            String activeRun,
            String error,
            List<AgentSession.Task> tasks) {
        var facts = log.events();
        var turns = new LinkedHashMap<String, Turn>();
        long checkpoint = 0;
        for (var fact : facts) {
            if (fact.type().equals("state/checkpoint")) checkpoint = ordinal(fact);
            if (fact.turnId() == null) continue;
            if (fact.data() instanceof SessionExecutionData.TurnStart)
                turns.put(
                        fact.turnId(),
                        new Turn(
                                fact.turnId(),
                                fact.executionRunId(),
                                activeRun == null || activeRun.equals(fact.executionRunId())
                                        ? "running"
                                        : "interrupted"));
            if (fact.type().equals("run/end"))
                turns.put(
                        fact.turnId(),
                        new Turn(fact.turnId(), fact.executionRunId(), statusOf(fact)));
        }
        AgentState state = null;
        for (int i = facts.size() - 1; i >= 0; i--) {
            if (facts.get(i).data() instanceof SessionEventData.PayloadData payload
                    && "state/checkpoint".equals(payload.type())
                    && payload.fields().get("stateJson") instanceof String json
                    && !json.isBlank()) {
                state = AgentState.fromJsonString(json);
                break;
            }
        }
        List<Msg> messages = MessageDerivation.history(log);
        var pending = new ArrayList<SessionEvent>();
        for (var fact : facts) {
            if (fact.data() instanceof SessionEventData.PayloadData payload
                    && "interaction/requested".equals(payload.type())) {
                pending.add(fact);
            }
            if (fact.data() instanceof SessionEventData.PayloadData payload
                    && "interaction/resolved".equals(payload.type())) {
                Object id = payload.fields().get("requestId");
                pending.removeIf(
                        event ->
                                id != null
                                        && id.equals(
                                                ((SessionEventData.PayloadData) event.data())
                                                        .fields()
                                                        .get("requestId")));
            }
        }
        return new Snapshot(
                sessionId,
                cursor(sessionId, log.size()),
                messages,
                ChatItems.read(facts),
                List.copyOf(turns.values()),
                pending.stream().map(ChatHistory::frame).toList(),
                facts.stream()
                        .skip(Math.max(0, facts.size() - 80))
                        .map(ChatHistory::frame)
                        .toList(),
                checkpoint,
                state == null ? 0 : state.getContext().size(),
                java.util.Set.of(),
                error,
                tasks);
    }

    private static String statusOf(SessionEvent fact) {
        if (fact.data() instanceof SessionEventData.PayloadData payload
                && payload.fields().get("status") != null) {
            return String.valueOf(payload.fields().get("status"));
        }
        return "completed";
    }

    static String cursor(String session, long seq) {
        return session + ":" + seq;
    }

    static long position(String session, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.startsWith(session + ":"))
            throw new IllegalArgumentException("Cursor belongs to another session");
        long seq = Long.parseLong(cursor.substring(session.length() + 1));
        if (seq < 0) throw new IllegalArgumentException("Invalid cursor");
        return seq;
    }

    /** The 1-based ordinal of one committed event, used as this read model's cursor position. */
    static long ordinal(SessionEvent fact) {
        return fact.seq().value() + 1;
    }

    static Frame frame(SessionEvent fact) {
        var data = new LinkedHashMap<String, Object>();
        var payload =
                fact.data() instanceof SessionEventData.PayloadData body
                        ? body.fields()
                        : java.util.Map.<String, Object>of();
        // The live trace shows identity/boundaries; large requests and checkpoints are read on
        // demand.
        for (String key :
                List.of(
                        "message",
                        "requestId",
                        "kind",
                        "call",
                        "status",
                        "reason",
                        "modelCallId",
                        "toolCallId"))
            if (payload.get(key) != null) data.put(key, payload.get(key));
        return new Frame(
                ordinal(fact),
                fact.eventId(),
                fact.type(),
                fact.turnId(),
                fact.executionRunId(),
                data);
    }
}
