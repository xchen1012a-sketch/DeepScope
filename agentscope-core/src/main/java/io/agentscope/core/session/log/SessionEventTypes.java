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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The session event vocabulary, grouped by the role each family plays.
 *
 * <p>The grouping is the point: only the types returned by {@link #surfaceTypes()} produce
 * model-visible messages and may carry a {@link SurfaceOp}. Everything else is replay, diagnostic,
 * or extension data and must not pretend to be a message.
 *
 * <p>A type outside this catalog is an extension record. It is accepted only when the event envelope
 * marks it {@link SessionEvent#ignorable()}; an unrecognized required record makes reconstruction
 * fail rather than silently drop a fact that may change how the rest of the log reads.
 */
public final class SessionEventTypes {

    private SessionEventTypes() {}

    /** A user-role message on the model-visible surface. */
    public static final String USER_MESSAGE = "user/message";

    /** An incremental session change admitted at a turn and step. */
    public static final String DEVELOPER_MESSAGE = "developer/message";

    /** The rendered system prompt. */
    public static final String SYSTEM_MESSAGE = "system/message";

    /** The assembled assistant message for one model call. */
    public static final String ASSISTANT_MESSAGE = "assistant/message";

    /** A completed tool call's model-facing result. */
    public static final String TOOL_RESULT = "tool/result";

    /** Opens a turn before queued input is claimed or pre-step runs. */
    public static final String TURN_START = "turn/start";

    /** Continues a turn that already has a start record. */
    public static final String TURN_RESUMED = "turn/resumed";

    /** Closes a turn with the reason that ended it. */
    public static final String TURN_END = "turn/end";

    /** Opens one step: a model call plus the tool executions it requested. */
    public static final String STEP_START = "step/start";

    /** Closes one step. */
    public static final String STEP_END = "step/end";

    /** The model requested one tool invocation. */
    public static final String TOOL_CALL = "tool/call";

    /** One model attempt that reached settlement without producing a surface message. */
    public static final String ASSISTANT_ATTEMPT = "assistant/attempt";

    /** A full header snapshot for the next request. */
    public static final String REQUEST_HEADER = "request/header";

    /** Route metadata, logged only when the route or its capacity changes. */
    public static final String REQUEST_CONTEXT = "request/context";

    /** Separates inherited or restored history from later lifecycle-owned work. */
    public static final String END_SEED = "session/end-seed";

    /** A submitted task awaiting its turn. */
    public static final String INPUT_SUBMITTED = "input/submitted";

    /** Guidance that adjusts work already running rather than starting its own turn. */
    public static final String INPUT_STEERED = "input/steered";

    /** Material recorded for later use. */
    public static final String INPUT_INJECTED = "input/injected";

    /** A request the session refused, with the reason. */
    public static final String INPUT_REJECTED = "input/rejected";

    /** The event types that produce model-visible messages, in catalog order. */
    private static final Set<String> SURFACE_TYPES =
            Set.of(SYSTEM_MESSAGE, DEVELOPER_MESSAGE, USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_RESULT);

    /** The lifecycle and diagnostic types the kernel itself knows. */
    private static final Set<String> KNOWN_TYPES =
            Set.of(
                    SYSTEM_MESSAGE,
                    DEVELOPER_MESSAGE,
                    USER_MESSAGE,
                    ASSISTANT_MESSAGE,
                    TOOL_RESULT,
                    TURN_START,
                    TURN_RESUMED,
                    TURN_END,
                    STEP_START,
                    STEP_END,
                    TOOL_CALL,
                    ASSISTANT_ATTEMPT,
                    REQUEST_HEADER,
                    REQUEST_CONTEXT,
                    END_SEED,
                    INPUT_SUBMITTED,
                    INPUT_STEERED,
                    INPUT_INJECTED,
                    INPUT_REJECTED);

    /**
     * Whether an event type produces a model-visible message.
     *
     * @param type the event type to test
     * @return true for one of the message-producing types
     */
    public static boolean isSurfaceEligible(String type) {
        return SURFACE_TYPES.contains(type);
    }

    /**
     * Whether the kernel knows an event type.
     *
     * @param type the event type to test
     * @return true when the type is catalogued rather than an extension record
     */
    public static boolean isKnown(String type) {
        return KNOWN_TYPES.contains(type);
    }

    /** The message-producing event types, in catalog order. */
    public static Set<String> surfaceTypes() {
        return new LinkedHashSet<>(SURFACE_TYPES);
    }

    /** Every catalogued event type. */
    public static Set<String> knownTypes() {
        return new LinkedHashSet<>(KNOWN_TYPES);
    }
}
