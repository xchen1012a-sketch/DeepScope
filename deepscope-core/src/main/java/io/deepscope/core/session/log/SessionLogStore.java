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

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.session.SessionKey;
import java.util.List;

/**
 * The kernel session log backend: writers, external appends, ownership, and reads.
 *
 * <p>Opening a reader must not acquire a writer or repair history. A store resolves the caller's
 * storage namespace from the {@link RuntimeContext}, so every method takes it.
 */
public interface SessionLogStore {
    /**
     * Open the kernel writer for one session.
     *
     * @param key session identity
     * @param runId execution that will append
     * @param turnId logical turn, or null
     * @return the kernel writer
     */
    DurableSession openWriter(SessionKey key, String runId, String turnId, RuntimeContext context);

    /** Append a kernel payload without becoming the execution writer. */
    void appendExternal(
            SessionKey key, SessionEventData data, String turnId, RuntimeContext context);

    /** Read the kernel log without claiming the execution writer. */
    SessionLog readKernel(SessionKey key, RuntimeContext context);

    /**
     * The run id that currently holds this session's write ownership, or null when no execution is
     * writing. Callers use this instead of opening a reader to ask whether the session is busy.
     */
    String owner(SessionKey key, RuntimeContext context);

    /**
     * Discover native sessions in the caller's storage namespace, without acquiring writers.
     * Implementations should restrict results to the caller's user identity. This is a discovery
     * view, not a transactional snapshot; a session created concurrently may appear on a later call.
     */
    default List<SessionKey> list(RuntimeContext context) {
        throw new UnsupportedOperationException(
                "This session log store does not support discovery");
    }
}
