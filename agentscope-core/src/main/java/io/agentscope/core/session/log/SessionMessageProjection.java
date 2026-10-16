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

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

/**
 * A pure interpretation of one extension event that changes existing message content.
 *
 * <p>An extension that rewrites model-visible content registers one definition per event type. The
 * session calls it before accepting an event, so a content change can never be stored without its
 * interpretation, and a reader that lacks the owning definition refuses to read rather than showing
 * stale content.
 *
 * <p>The implementation must validate the complete decision before returning, preserve message
 * identities, and publish copies rather than mutating the supplied history.
 */
public interface SessionMessageProjection {

    /** The event type this definition interprets. */
    String type();

    /**
     * Interpret one event against the history preceding it.
     *
     * @param event the candidate event, not yet applied to the supplied history
     * @param context the committed history before this decision
     * @return the changed current messages, keyed by the sequence of the event that produced each
     * @throws IllegalArgumentException when the decision cannot be applied to this history
     */
    Map<SessionSeq.Seq, Msg> project(SessionEvent event, Context context);

    /** The history immediately before a message-projection event. */
    record Context(
            List<SessionSeq.Seq> nodes,
            List<SessionEvent> events,
            Map<SessionSeq.Seq, Msg> messages) {}
}
