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

/**
 * The durable operations the recording protocol needs from a kernel store.
 *
 * <p>Two stores serve the kernel — one over local files, one over an atomic key-value capability —
 * and the protocol above must not care which. This seam names exactly what the protocol uses, so a
 * deployment behind a remote workspace reaches the same kernel as a local one.
 */
public interface KernelJournal extends AutoCloseable {

    /** The session this journal belongs to. */
    String sessionId();

    /** Whether this journal may append. */
    boolean isWritable();

    /**
     * Events already committed in this session, in order.
     *
     * <p>A journal opened over stored records has replayed them. Callers that derive history must
     * start from this list, or a reopened session looks empty.
     */
    List<SessionEvent> committed();

    /**
     * Commit an event already validated against this session's surface.
     *
     * @param event the validated event, whose position must be the next one
     */
    void commitValidated(SessionEvent event);

    /** Durability barrier: every committed append is stored when this returns. */
    void flush();

    @Override
    void close();
}
