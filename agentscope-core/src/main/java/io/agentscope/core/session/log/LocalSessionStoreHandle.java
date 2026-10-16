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

/** Presents a local file handle as the journal seam the recording protocol uses. */
final class LocalSessionStoreHandle {

    private LocalSessionStoreHandle() {}

    /**
     * Adapt one local file handle.
     *
     * @param handle a handle opened for writing
     * @return the journal seam over that handle
     */
    static KernelJournal asJournal(LocalSessionStore.Handle handle) {
        Objects.requireNonNull(handle, "handle");
        return new KernelJournal() {
            @Override
            public String sessionId() {
                return handle.sessionId();
            }

            @Override
            public boolean isWritable() {
                return handle.isWritable();
            }

            @Override
            public List<SessionEvent> committed() {
                return handle.log().events();
            }

            @Override
            public void commitValidated(SessionEvent event) {
                handle.commitValidated(event);
            }

            @Override
            public void flush() {
                handle.flush();
            }

            @Override
            public void close() {
                handle.close();
            }
        };
    }
}
