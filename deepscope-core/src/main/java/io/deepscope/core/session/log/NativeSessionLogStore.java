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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The kernel's session log store.
 *
 * <p>The kernel owns the surface and derives model history, so callers open a writer, append kernel
 * payloads, and read the kernel log directly. The session id is the store's directory segment.
 */
public final class NativeSessionLogStore implements SessionLogStore {

    private final SessionJournalStore store;
    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DurableSession> liveWriters = new ConcurrentHashMap<>();

    /**
     * Serve the recording protocol from one kernel store.
     *
     * @param store the kernel's durable store
     */
    public NativeSessionLogStore(SessionJournalStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The kernel store behind this adapter. */
    public SessionJournalStore store() {
        return store;
    }

    /**
     * Open a kernel writer for one session. The session is created when it does not exist yet.
     *
     * @param sessionId store session id
     * @param runId execution that will append
     * @param turnId logical turn, or null when the caller has not chosen one
     * @return the writer; the caller closes it
     */
    public DurableSession openWriter(String sessionId, String runId, String turnId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (!store.list().contains(sessionId)) {
            try {
                store.create(sessionId);
            } catch (RuntimeException error) {
                if (!store.list().contains(sessionId)) {
                    throw error;
                }
            }
        }
        KernelJournal journal;
        try {
            journal = store.open(sessionId, SessionJournalStore.Access.WRITE);
        } catch (RuntimeException error) {
            if ("SessionAlreadyOwnedException".equals(error.getClass().getSimpleName())) {
                throw new SessionLogException("Session has an active writer: " + sessionId, error);
            }
            throw error;
        }
        try {
            DurableSession session = new DurableSession(this, sessionId, journal, runId, turnId);
            liveWriters.put(sessionId, session);
            owners.put(sessionId, runId);
            return session;
        } catch (RuntimeException error) {
            journal.close();
            throw error;
        }
    }

    /**
     * Append one payload while this session's writer is already open, or with a short-lived writer
     * when nothing is running.
     *
     * @param sessionId store session id
     * @param data kernel payload
     * @param turnId logical turn stamped on the event when no writer is open
     */
    public void appendExternal(String sessionId, SessionEventData data, String turnId) {
        DurableSession live = liveWriters.get(sessionId);
        if (live != null) {
            live.append(data);
            return;
        }
        try (DurableSession temporary =
                openWriter(sessionId, "external-" + java.util.UUID.randomUUID(), turnId)) {
            temporary.append(data);
        }
    }

    /**
     * The run id that currently holds this session's write ownership, or null when no execution is
     * writing.
     *
     * @param sessionId store session id
     */
    public String owner(String sessionId) {
        return owners.get(sessionId);
    }

    /** Read committed events without taking the write lock for longer than the copy. */
    public SessionLog read(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (!store.list().contains(sessionId)) {
            return new SessionLog();
        }
        try (KernelJournal journal = store.open(sessionId, SessionJournalStore.Access.READ)) {
            SessionLog loaded = new SessionLog();
            for (io.deepscope.core.session.log.SessionEvent event : journal.committed()) {
                loaded.append(event);
            }
            return loaded;
        }
    }

    void detach(String sessionId, DurableSession session) {
        liveWriters.remove(sessionId, session);
        owners.remove(sessionId, session.runId());
    }

    @Override
    public DurableSession openWriter(
            SessionKey key, String runId, String turnId, RuntimeContext context) {
        return openWriter(key.sessionId(), runId, turnId);
    }

    @Override
    public void appendExternal(
            SessionKey key, SessionEventData data, String turnId, RuntimeContext context) {
        appendExternal(key.sessionId(), data, turnId);
    }

    @Override
    public SessionLog readKernel(SessionKey key, RuntimeContext context) {
        return read(key.sessionId());
    }

    @Override
    public String owner(SessionKey key, RuntimeContext context) {
        return owner(key.sessionId());
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        List<SessionKey> keys = new ArrayList<>();
        for (String sessionId : store.list()) {
            keys.add(new SessionKey(null, null, sessionId));
        }
        return List.copyOf(keys);
    }
}
