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

/**
 * A kernel store the recording protocol can be mounted on.
 *
 * <p>Two stores serve the kernel — one over local files, one over an atomic key-value capability —
 * and a deployment must be able to choose either without the layer above noticing. This seam names
 * what the protocol needs from a store, so a workspace that lives on remote storage reaches the same
 * kernel as one on a local disk.
 */
public interface SessionJournalStore {

    /** How a caller intends to use a session handle. */
    enum Access {
        /** Observe committed records without claiming write ownership. */
        READ,
        /** Claim single-writer ownership and append. */
        WRITE
    }

    /**
     * Create an empty session.
     *
     * @param sessionId the new session's id
     * @throws IllegalArgumentException when the id is not one safe path segment
     */
    void create(String sessionId);

    /**
     * Open a stored session.
     *
     * @param sessionId the session to open
     * @param access whether to claim write ownership
     * @return the journal to append through or read from
     * @throws RuntimeException when the session is absent, already owned, or unreadable
     */
    KernelJournal open(String sessionId, Access access);

    /** Every readable stored session id. */
    List<String> list();
}
