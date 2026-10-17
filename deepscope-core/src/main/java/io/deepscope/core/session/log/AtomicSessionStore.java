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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.core.session.AtomicSessionStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable session storage over an atomic key-value capability.
 *
 * <p>The same kernel as the local file store, served through the capability a workspace exposes
 * instead of a local directory. A deployment whose workspace is remote therefore keeps session
 * history without a local disk, and the kernel is unchanged: it appends events and derives messages
 * the same way regardless of what stores the records.
 *
 * <p>Layout per session: a header object holding the format version and the committed record count,
 * and one object per committed event. Publishing a batch advances the header with a compare-and-set,
 * so a concurrent writer's batch is rejected rather than overwritten.
 *
 * <p>The capability must durably store bytes before acknowledging a write, and its namespace must not
 * be reachable through model filesystem tools.
 */
public final class AtomicSessionStore implements SessionJournalStore {

    /** Prefix every object of this store shares, so a shared namespace stays partitioned. */
    public static final String ROOT = "sessions/";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PUBLISH_ATTEMPTS = 32;

    private final AtomicSessionStorage storage;
    private final Set<String> writers = ConcurrentHashMap.newKeySet();

    /**
     * Serve the kernel from one atomic storage capability.
     *
     * @param storage the capability that stores and publishes records
     */
    public AtomicSessionStore(AtomicSessionStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** How a caller intends to use a session handle, mirroring the store seam. */
    public enum Access {
        /** Observe committed records without claiming write ownership. */
        READ,
        /** Claim single-writer ownership and append. */
        WRITE
    }

    /** A stored session's metadata, read without loading its events. */
    public record Stats(String sessionId, int version, long eventCount) {}

    /**
     * Create an empty session, materializing its header.
     *
     * @param sessionId the new session's id
     * @throws SessionAlreadyExistsException when the session is already stored
     */
    @Override
    public void create(String sessionId) {
        requireSafeId(sessionId);
        if (storage.read(headerPath(sessionId)) != null) {
            throw new SessionAlreadyExistsException("Session already exists: " + sessionId);
        }
        publishHeader(sessionId, 0L, 0L);
    }

    /**
     * Open a stored session.
     *
     * @param sessionId the session to open
     * @param access whether to claim write ownership
     * @return the handle, which the caller closes
     */
    @Override
    public KernelJournal open(String sessionId, SessionJournalStore.Access access) {
        return open(
                        sessionId,
                        access == SessionJournalStore.Access.WRITE ? Access.WRITE : Access.READ)
                .asJournal();
    }

    /**
     * Open a stored session.
     *
     * @param sessionId the session to open
     * @param access whether to claim write ownership
     * @return the handle, which the caller closes
     */
    public Handle open(String sessionId, Access access) {
        requireSafeId(sessionId);
        AtomicSessionStorage.Value header = storage.read(headerPath(sessionId));
        if (header == null) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        Map<String, Object> fields = parseMap(header.bytes(), sessionId);
        int version = intOf(fields.get("version"));
        if (version != SessionFormatVersion.CURRENT) {
            throw new LocalSessionStore.FormatUnsupportedException(
                    "Session "
                            + sessionId
                            + " is stored in format version "
                            + version
                            + "; this build reads version "
                            + SessionFormatVersion.CURRENT);
        }
        long count = longOf(fields.get("count"));
        SessionLog log = new SessionLog();
        for (long seq = 0; seq < count; seq++) {
            AtomicSessionStorage.Value record = storage.read(eventPath(sessionId, seq));
            if (record == null) {
                throw new SessionCorruptionException(
                        "Session " + sessionId + " is missing event " + seq);
            }
            log.append(decode(record.bytes(), sessionId, seq));
        }
        if (access == Access.WRITE && !writers.add(sessionId)) {
            throw new SessionAlreadyOwnedException("Session is already owned: " + sessionId);
        }
        return new Handle(sessionId, log, access == Access.WRITE, count);
    }

    /** Every readable stored session id, in order. */
    @Override
    public List<String> list() {
        List<String> ids = new ArrayList<>();
        for (String path : storage.listPaths(ROOT)) {
            if (!path.endsWith("/header.json")) {
                continue;
            }
            String id = path.substring(ROOT.length(), path.length() - "/header.json".length());
            if (!id.isBlank() && !id.contains("/")) {
                ids.add(id);
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    /** Read one stored session's metadata without loading its events. */
    public Stats stat(String sessionId) {
        requireSafeId(sessionId);
        AtomicSessionStorage.Value header = storage.read(headerPath(sessionId));
        if (header == null) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        Map<String, Object> fields = parseMap(header.bytes(), sessionId);
        return new Stats(sessionId, intOf(fields.get("version")), longOf(fields.get("count")));
    }

    /** The object path holding one committed event, exposed so tooling and tests can inspect it. */
    public String eventPath(String sessionId, long seq) {
        return ROOT + sessionId + "/events/" + seq + ".json";
    }

    /** The object path holding a session's header. */
    public String headerPath(String sessionId) {
        return ROOT + sessionId + "/header.json";
    }

    /** One open session. */
    public final class Handle implements AutoCloseable {

        private final String sessionId;
        private final SessionLog log;
        private final boolean writable;
        private long committedCount;
        private boolean closed;

        private Handle(String sessionId, SessionLog log, boolean writable, long committedCount) {
            this.sessionId = sessionId;
            this.log = log;
            this.writable = writable;
            this.committedCount = committedCount;
        }

        /** The session's id. */
        public String sessionId() {
            return sessionId;
        }

        /** Whether this handle may append. */
        public boolean isWritable() {
            return writable;
        }

        /** The committed log, which reflects every append this handle made. */
        public SessionLog log() {
            requireOpen();
            return log;
        }

        /**
         * Append one message-producing event and persist it.
         *
         * @param time Unix epoch milliseconds
         * @param data the message-producing payload
         */
        public void append(long time, SessionEventData.SurfaceData data) {
            requireOpen();
            requireWritable();
            writeEvent(
                    SessionEvent.surface(log.nextSeq(), time, data, SurfaceOp.APPEND, List.of()));
        }

        /** Append a log-only event and persist it. */
        public void appendLogOnly(long time, SessionEventData data) {
            requireOpen();
            requireWritable();
            writeEvent(SessionEvent.logOnly(log.nextSeq(), time, data));
        }

        /** Commit an event already validated against this session's surface. */
        public void commitValidated(SessionEvent event) {
            requireOpen();
            requireWritable();
            writeEvent(event);
        }

        /**
         * Durability barrier: every committed append is stored when this returns.
         *
         * <p>Each append publishes through the capability before it resolves, so nothing is pending.
         */
        public void flush() {
            requireOpen();
            requireWritable();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (writable) {
                writers.remove(sessionId);
            }
        }

        private void writeEvent(SessionEvent event) {
            // Validate and append in memory first, so a rejected event leaves nothing behind.
            log.append(event);
            byte[] body = encode(event, sessionId);
            storage.compareAndSet(eventPath(sessionId, committedCount), 0L, body);
            publishHeader(sessionId, committedCount, committedCount + 1);
            committedCount++;
        }

        /** Present this handle as the journal seam the recording protocol uses. */
        public KernelJournal asJournal() {
            return new KernelJournal() {
                @Override
                public String sessionId() {
                    return sessionId;
                }

                @Override
                public boolean isWritable() {
                    return writable;
                }

                @Override
                public List<SessionEvent> committed() {
                    return log().events();
                }

                @Override
                public void commitValidated(SessionEvent event) {
                    Handle.this.commitValidated(event);
                }

                @Override
                public void flush() {
                    Handle.this.flush();
                }

                @Override
                public void close() {
                    Handle.this.close();
                }
            };
        }

        private void requireOpen() {
            if (closed) {
                throw new LocalSessionStore.SessionHandleClosedException(
                        "Session handle is closed: " + sessionId);
            }
        }

        private void requireWritable() {
            if (!writable) {
                throw new LocalSessionStore.SessionReadOnlyException(
                        "Session handle is read-only: " + sessionId);
            }
        }
    }

    /** Advance the header, retrying a lost race rather than overwriting another writer's batch. */
    private void publishHeader(String sessionId, long expectedCount, long nextCount) {
        for (int attempt = 0; attempt < PUBLISH_ATTEMPTS; attempt++) {
            AtomicSessionStorage.Value current = storage.read(headerPath(sessionId));
            long expectedVersion = current == null ? 0L : current.version();
            if (current != null
                    && longOf(parseMap(current.bytes(), sessionId).get("count")) != expectedCount) {
                throw new SessionOwnershipLostException(
                        "Another writer advanced session " + sessionId);
            }
            if (storage.compareAndSet(
                    headerPath(sessionId), expectedVersion, headerBytes(nextCount))) {
                return;
            }
        }
        throw new SessionOwnershipLostException(
                "Could not publish the header of session "
                        + sessionId
                        + " after repeated attempts");
    }

    private byte[] headerBytes(long count) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("version", SessionFormatVersion.CURRENT);
        header.put("count", count);
        return toBytes(header, "header");
    }

    private byte[] encode(SessionEvent event, String sessionId) {
        return toBytes(SessionEventCodec.encode(event, MAPPER), "event of " + sessionId);
    }

    private SessionEvent decode(byte[] body, String sessionId, long seq) {
        try {
            return SessionEventCodec.decode(MAPPER.readValue(body, Map.class), MAPPER, (int) seq);
        } catch (IOException unreadable) {
            throw new SessionCorruptionException(
                    "Session " + sessionId + " has an unreadable event at " + seq);
        }
    }

    private byte[] toBytes(Object value, String subject) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not encode the " + subject, failure);
        }
    }

    private Map<String, Object> parseMap(byte[] body, String sessionId) {
        try {
            return MAPPER.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (IOException unreadable) {
            throw new SessionCorruptionException(
                    "Session " + sessionId + " has an unreadable header");
        }
    }

    private static int intOf(Object raw) {
        return raw instanceof Number value ? value.intValue() : 0;
    }

    private static long longOf(Object raw) {
        return raw instanceof Number value ? value.longValue() : 0L;
    }

    private static void requireSafeId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()
                || sessionId.contains("/")
                || sessionId.contains("\\")
                || sessionId.equals(".")
                || sessionId.equals("..")) {
            throw new IllegalArgumentException("Session id must be one path segment: " + sessionId);
        }
    }

    /** The session does not exist. */
    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) {
            super(message);
        }
    }

    /** A session with that id already exists. */
    public static class SessionAlreadyExistsException extends RuntimeException {
        public SessionAlreadyExistsException(String message) {
            super(message);
        }
    }

    /** Another writer already owns the session. */
    public static class SessionAlreadyOwnedException extends RuntimeException {
        public SessionAlreadyOwnedException(String message) {
            super(message);
        }
    }

    /** Write ownership was lost to another writer. */
    public static class SessionOwnershipLostException extends RuntimeException {
        public SessionOwnershipLostException(String message) {
            super(message);
        }
    }

    /** The stored records are damaged. */
    public static class SessionCorruptionException extends RuntimeException {
        public SessionCorruptionException(String message) {
            super(message);
        }
    }
}
