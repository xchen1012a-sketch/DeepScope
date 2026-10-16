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

import java.util.List;
import java.util.Objects;

/**
 * Payloads describing one execution's structure: turn and step boundaries, model attempts, and tool
 * requests.
 *
 * <p>None of these produce a model-visible message. They locate surface events in a turn and a step,
 * pair a tool request with its result, and let a reader close an execution that never finished.
 */
public final class SessionExecutionData {

    private SessionExecutionData() {}

    /**
     * Opens a turn before queued input is claimed or pre-step runs.
     *
     * @param turn the turn number, counted from one within the session
     */
    public record TurnStart(long turn, boolean resumed) implements SessionEventData {
        public TurnStart {
            requireNonNegative(turn, "turn");
        }

        /** Opens a turn. A resumed continuation uses {@link #TurnStart(long, boolean)}. */
        public TurnStart(long turn) {
            this(turn, false);
        }

        @Override
        public String type() {
            return resumed ? SessionEventTypes.TURN_RESUMED : SessionEventTypes.TURN_START;
        }
    }

    /**
     * Closes a turn with the reason that ended it.
     *
     * <p>{@link TurnEndReason#INTERRUPTED} and {@link TurnEndReason#FORKED} are synthetic: crash
     * recovery appends the first and fork-seed construction the second. The loop never emits either,
     * so a reader can tell a genuine end from a repaired one.
     *
     * @param turn the closed turn
     * @param reason why it ended
     */
    public record TurnEnd(long turn, TurnEndReason reason) implements SessionEventData {
        public TurnEnd {
            requireNonNegative(turn, "turn");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String type() {
            return SessionEventTypes.TURN_END;
        }
    }

    /** Why a turn ended. */
    public enum TurnEndReason {
        /** The turn finished normally. */
        COMPLETED,
        /** A cancellation request stopped the live turn. */
        ABORTED,
        /** The turn could not proceed. */
        BLOCKED,
        /** The turn failed. */
        ERROR,
        /** At least one step reached its output-token ceiling, even if a later step continued. */
        MAX_TOKENS,
        /**
         * The turn paused to wait for a person: a question, an approval, or an external result.
         *
         * <p>Distinct from {@link #BLOCKED}: waiting for a person is an expected pause that a later
         * answer continues, while a blocked turn could not proceed. Recording both as one outcome
         * would make the two indistinguishable to a reader.
         */
        SUSPENDED,
        /** A crash-orphaned turn was closed after the fact. Only recovery emits this. */
        INTERRUPTED,
        /** A fork seed closed a turn still open at the fork boundary. Only fork emits this. */
        FORKED
    }

    /**
     * Opens one step: a model call plus the tool executions it requested.
     *
     * @param turn the enclosing turn
     * @param step the step number within the turn, counted from one
     */
    public record StepStart(long turn, long step) implements SessionEventData {
        public StepStart {
            requireNonNegative(turn, "turn");
            requireNonNegative(step, "step");
        }

        @Override
        public String type() {
            return SessionEventTypes.STEP_START;
        }
    }

    /**
     * Closes one step.
     *
     * @param turn the enclosing turn
     * @param step the closed step
     */
    public record StepEnd(long turn, long step) implements SessionEventData {
        public StepEnd {
            requireNonNegative(turn, "turn");
            requireNonNegative(step, "step");
        }

        @Override
        public String type() {
            return SessionEventTypes.STEP_END;
        }
    }

    /**
     * The model requested one tool invocation.
     *
     * @param turn the enclosing turn
     * @param step the enclosing step
     * @param callId pairs the request with its result; the same value appears on every phase
     * @param toolName the invoked tool
     * @param arguments the raw argument JSON exactly as the model produced it, unparsed; empty when
     *     the model streamed none
     */
    public record ToolCall(long turn, long step, String callId, String toolName, String arguments)
            implements SessionEventData {
        public ToolCall {
            requireNonNegative(turn, "turn");
            requireNonNegative(step, "step");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            arguments = arguments == null ? "" : arguments;
        }

        @Override
        public String type() {
            return SessionEventTypes.TOOL_CALL;
        }
    }

    /**
     * One model attempt that reached settlement without producing a surface message.
     *
     * <p>The embedded stream preserves a failed, retried, cancelled, or stream-error attempt without
     * fabricating model history, so token accounting and diagnostics survive without the model ever
     * seeing the attempt again.
     *
     * @param turn the enclosing turn
     * @param step the enclosing step
     * @param stream the exact compact provider stream
     */
    public record AssistantAttempt(long turn, long step, List<AssistantStreamRecord> stream)
            implements SessionEventData {
        public AssistantAttempt {
            requireNonNegative(turn, "turn");
            requireNonNegative(step, "step");
            stream = stream == null ? List.of() : List.copyOf(stream);
        }

        @Override
        public String type() {
            return SessionEventTypes.ASSISTANT_ATTEMPT;
        }
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative, got " + value);
        }
    }
}
