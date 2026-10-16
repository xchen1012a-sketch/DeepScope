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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closes an execution the log left open, supplying only what is missing.
 *
 * <p>A committed prefix may end inside a turn because a process died or because a fork cut the log
 * there. Repair appends synthetic tool results for unanswered requests, then closes the open step and
 * turn. It never rewrites a closed step, and a request that already has a result is left alone.
 *
 * <p>A recorded request without a durable result must not be retried blindly: the synthetic result
 * states what the log does and does not know, and the caller decides whether retrying is safe.
 */
public final class TurnRepair {

    private TurnRepair() {}

    /** Recovery code for an assistant tool request that never reached a recorded call start. */
    public static final String TOOL_NOT_STARTED = "TOOL_NOT_STARTED";

    /** Recovery code for a recorded tool call whose completed outcome was not durably recorded. */
    public static final String TOOL_OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN";

    /** Why an open tail is being closed. The cause selects wording and the turn-ending reason. */
    public enum CloseCause {
        /** Crash recovery over a persisted log. */
        INTERRUPTED,
        /** A fork seed cut inside the source's open turn. */
        FORKED
    }

    private static final String INTERRUPTED_STARTED =
            "The tool call was interrupted after it was recorded, but no result was durably"
                + " recorded. Its outcome is unknown. Decide whether to retry from the tool"
                + " semantics: retry only if the operation is read-only or idempotent; if it may"
                + " have side effects, first verify external state or ask the user. Do not retry"
                + " blindly.";

    private static final String INTERRUPTED_NOT_STARTED =
            "The tool call was interrupted before it was recorded as started. Retry it if it is"
                    + " still needed.";

    private static final String FORKED_STARTED =
            "The history inherited by this branch records this tool call starting but does not"
                    + " include its result. The parent session may have completed it after the fork"
                    + " point. Decide whether to retry from the tool semantics: retry only if the"
                    + " operation is read-only or idempotent; if it may have side effects, first"
                    + " verify external state or ask the user. Do not retry blindly.";

    private static final String FORKED_NOT_STARTED =
            "The history inherited by this branch has no record of this tool call starting. The"
                    + " parent session may have executed it after the fork point. Decide whether to"
                    + " retry from the tool semantics: retry only if the operation is read-only or"
                    + " idempotent; if it may have side effects, first verify external state or ask"
                    + " the user. Do not retry blindly.";

    /**
     * Crash-recovery entry point.
     *
     * @param events the committed prefix, which may end inside an open turn
     * @return the synthetic closers to append, in order; empty when the log is already balanced
     */
    public static List<SessionEvent> interruptedTurnClosers(List<SessionEvent> events) {
        return openTurnClosers(events, CloseCause.INTERRUPTED);
    }

    /**
     * Return deterministic synthetic events that close an open tail turn.
     *
     * <p>Unanswered tool requests become error results first, then an open step closes, then the turn
     * closes with a cause-specific reason. Sequences continue the log and timestamps reuse the last
     * real event, so a repair is reproducible and never invents a future time.
     *
     * @param events the committed prefix to scan
     * @param cause the owning close operation
     * @return the synthetic closers to append, in order; empty when the log is already balanced
     */
    public static List<SessionEvent> openTurnClosers(List<SessionEvent> events, CloseCause cause) {
        Long openTurn = null;
        Long openStep = null;
        ToolCallRecovery recovery = new ToolCallRecovery(cause);
        for (SessionEvent event : events) {
            recovery.observe(event);
            if (event.data() instanceof SessionExecutionData.TurnStart start) {
                openTurn = start.turn();
                openStep = null;
            } else if (event.data() instanceof SessionExecutionData.TurnEnd) {
                openTurn = null;
                openStep = null;
            } else if (event.data() instanceof SessionExecutionData.StepStart start) {
                openStep = start.step();
            } else if (event.data() instanceof SessionExecutionData.StepEnd) {
                openStep = null;
            }
        }

        if (openTurn == null || events.isEmpty()) {
            return List.of();
        }

        SessionEvent last = events.get(events.size() - 1);
        List<SessionEvent> closers = new ArrayList<>(recovery.results());
        long seq = last.seq().value() + closers.size() + 1;
        long time = last.time();
        if (openStep != null) {
            closers.add(
                    SessionEvent.logOnly(
                            SessionSeq.Seq.of(seq++),
                            time,
                            new SessionExecutionData.StepEnd(openTurn, openStep)));
        }
        closers.add(
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(seq),
                        time,
                        new SessionExecutionData.TurnEnd(openTurn, reasonFor(cause))));
        return closers;
    }

    /**
     * Map the recording protocol's turn status onto the kernel's reason.
     *
     * <p>The protocol names a turn's outcome as a string; the kernel names it as a reason. A reader
     * must not guess which reason an unrecognized status meant, so an unknown status reports absence
     * and the caller decides.
     *
     * @param status the recorded status string
     * @return the matching reason, or empty when the status is not recognized
     */
    public static Optional<SessionExecutionData.TurnEndReason> reasonForStatus(String status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case "completed" -> Optional.of(SessionExecutionData.TurnEndReason.COMPLETED);
            case "suspended" -> Optional.of(SessionExecutionData.TurnEndReason.SUSPENDED);
            case "failed" -> Optional.of(SessionExecutionData.TurnEndReason.ERROR);
            case "cancelled" -> Optional.of(SessionExecutionData.TurnEndReason.ABORTED);
            case "interrupted" -> Optional.of(SessionExecutionData.TurnEndReason.INTERRUPTED);
            case "blocked" -> Optional.of(SessionExecutionData.TurnEndReason.BLOCKED);
            case "max_tokens" -> Optional.of(SessionExecutionData.TurnEndReason.MAX_TOKENS);
            default -> Optional.empty();
        };
    }

    private static SessionExecutionData.TurnEndReason reasonFor(CloseCause cause) {
        return cause == CloseCause.FORKED
                ? SessionExecutionData.TurnEndReason.FORKED
                : SessionExecutionData.TurnEndReason.INTERRUPTED;
    }

    /**
     * Tracks unanswered assistant tool requests from committed events.
     *
     * <p>Observe from the start of the owned step or the replay prefix, and recover before that step
     * closes. The tracker retains pending identities, not event history.
     */
    public static final class ToolCallRecovery {

        private final CloseCause cause;
        private final Map<String, Pending> pendingCalls = new LinkedHashMap<>();
        private SessionEvent last;

        /**
         * @param cause selects the wording of the synthetic results
         */
        public ToolCallRecovery(CloseCause cause) {
            this.cause = cause;
        }

        /**
         * Consume the next committed event. A closed step and a turn boundary discard pending
         * requests, because a closed step is never repaired.
         *
         * @param event the next event from the same session, in sequence order
         */
        public void observe(SessionEvent event) {
            last = event;
            if (event.data() instanceof SessionExecutionData.TurnStart
                    || event.data() instanceof SessionExecutionData.TurnEnd
                    || event.data() instanceof SessionExecutionData.StepEnd) {
                pendingCalls.clear();
                return;
            }
            if (event.data() instanceof SessionEventData.AssistantMessageData assistant) {
                for (var block : assistant.content()) {
                    if (block instanceof ToolUseBlock toolUse) {
                        Pending pending = new Pending(assistant.turn(), assistant.step());
                        pending.toolName = toolUse.getName();
                        pendingCalls.put(toolUse.getId(), pending);
                    }
                }
                return;
            }
            if (event.data() instanceof SessionExecutionData.ToolCall call) {
                Pending entry = pendingCalls.get(call.callId());
                if (entry != null) {
                    entry.callSeq = event.seq();
                }
                return;
            }
            if (event.data() instanceof SessionEventData.ToolResultData result
                    && event.surfaceOp() instanceof SurfaceOp.Append) {
                Pending entry = pendingCalls.get(result.callId());
                if (entry != null) {
                    pendingCalls.remove(result.callId());
                }
            }
        }

        /**
         * Build conservative error results in request order without changing tracked state.
         *
         * @return pending tool results continuing the latest observed sequence; empty when none remain
         */
        public List<SessionEvent> results() {
            if (last == null) {
                return List.of();
            }
            long seq = last.seq().value() + 1;
            long time = last.time();
            List<SessionEvent> results = new ArrayList<>(pendingCalls.size());
            for (Map.Entry<String, Pending> entry : pendingCalls.entrySet()) {
                String callId = entry.getKey();
                Pending pending = entry.getValue();
                boolean started = pending.callSeq != null;
                SessionEventData.ToolResultData data =
                        new SessionEventData.ToolResultData(
                                callId,
                                toolNameOf(callId),
                                List.of(
                                        ToolResultBlock.builder()
                                                .id(callId)
                                                .name(toolNameOf(callId))
                                                .output(
                                                        TextBlock.builder()
                                                                .text(wording(started))
                                                                .build())
                                                .state(ToolResultState.ERROR)
                                                .build()),
                                true,
                                new SessionEventData.ToolFailure(
                                        started ? "ToolOutcomeUnknownError" : "ToolNotStartedError",
                                        started ? TOOL_OUTCOME_UNKNOWN : TOOL_NOT_STARTED,
                                        null),
                                null);
                results.add(
                        SessionEvent.surface(
                                SessionSeq.Seq.of(seq++),
                                time,
                                data,
                                SurfaceOp.append(),
                                started ? List.of(pending.callSeq) : List.of()));
            }
            return results;
        }

        private String toolNameOf(String callId) {
            return pendingCalls.get(callId).toolName;
        }

        private String wording(boolean started) {
            if (cause == CloseCause.FORKED) {
                return started ? FORKED_STARTED : FORKED_NOT_STARTED;
            }
            return started ? INTERRUPTED_STARTED : INTERRUPTED_NOT_STARTED;
        }

        /** One request awaiting a result. */
        private static final class Pending {
            private final long turn;
            private final long step;
            private String toolName = "";
            private SessionSeq.Seq callSeq;

            private Pending(long turn, long step) {
                this.turn = turn;
                this.step = step;
            }
        }
    }
}
