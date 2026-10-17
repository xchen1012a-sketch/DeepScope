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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Durable session storage as one append-only log file per session.
 *
 * <p>Append-only shape: a header line carries the format version, and every committed event follows
 * on its own line. A committed line is never rewritten. Reads never expose a partial append, so a
 * torn final line is discarded rather than parsed.
 *
 * <p>Per-instance single-writer: {@link #open(String, Access)} claims write ownership, and a second
 * writer is refused instead of interleaving appends. A reader observes committed state and never
 * claims ownership, so several readers may run beside one writer.
 *
 * <p>A log whose version this build cannot faithfully interpret is refused rather than misread.
 */
public final class LocalSessionStore implements SessionJournalStore {

    /** The log file name inside a session directory. */
    public static final String LOG_FILE = "session.jsonl";

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> writers = ConcurrentHashMap.newKeySet();

    /**
     * Open a store rooted at a directory. The directory is created on first write.
     *
     * @param root the directory holding one subdirectory per session
     */
    public LocalSessionStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
    }

    /** How a caller intends to use a session handle, mirroring the store seam. */
    public enum Access {
        /** Observe committed state without claiming write ownership. */
        READ,
        /** Claim single-writer ownership and append. */
        WRITE
    }

    /** A stored session's metadata, read without loading its events. */
    public record Stats(String sessionId, int version, long eventCount, long sizeBytes) {}

    /**
     * Create an empty session and materialize its log, without claiming write ownership.
     *
     * <p>The caller opens it afterwards, which is what decides between reading and writing. Creating
     * and owning in one step would leave an unused claim behind whenever the caller only wanted to
     * define the session.
     *
     * @param sessionId the new session's id
     * @throws SessionAlreadyExistsException when a session with that id is already stored
     */
    @Override
    public void create(String sessionId) {
        requireSafeId(sessionId);
        Path directory = directoryOf(sessionId);
        if (Files.exists(directory)) {
            throw new SessionAlreadyExistsException("Session already exists: " + sessionId);
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    logPath(sessionId),
                    headerLine() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not create session " + sessionId, failure);
        }
    }

    /**
     * Open a stored session.
     *
     * @param sessionId the session to open
     * @param access whether to claim write ownership
     * @return the handle, which the caller closes
     * @throws SessionNotFoundException when the session has no stored log
     * @throws SessionAlreadyOwnedException when a writer already owns it
     * @throws FormatUnsupportedException when the stored version is not readable by this build
     */
    @Override
    public KernelJournal open(String sessionId, SessionJournalStore.Access access) {
        return LocalSessionStoreHandle.asJournal(
                open(
                        sessionId,
                        access == SessionJournalStore.Access.WRITE ? Access.WRITE : Access.READ));
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
        Path logFile = logPath(sessionId);
        if (!Files.isRegularFile(logFile)) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        List<String> lines = readLines(logFile);
        if (lines.isEmpty()) {
            throw new SessionCorruptionException("Session log is empty: " + sessionId);
        }
        int version = versionOf(lines.get(0), sessionId);
        if (version != SessionFormatVersion.CURRENT) {
            throw new FormatUnsupportedException(
                    "Session "
                            + sessionId
                            + " is stored in format version "
                            + version
                            + "; this build reads version "
                            + SessionFormatVersion.CURRENT);
        }
        SessionLog log = new SessionLog();
        for (int index = 1; index < lines.size(); index++) {
            SessionEvent event = decodeEvent(lines.get(index), sessionId, index - 1);
            log.append(event);
        }
        if (access == Access.WRITE && !writers.add(sessionId)) {
            throw new SessionAlreadyOwnedException("Session is already owned: " + sessionId);
        }
        return new Handle(sessionId, log, access == Access.WRITE, logFile);
    }

    /** Every readable stored session id. An unreadable log is omitted rather than reported. */
    @Override
    public List<String> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            List<String> ids =
                    children.filter(Files::isDirectory)
                            .map(path -> path.getFileName().toString())
                            .filter(
                                    id -> {
                                        try (Handle ignored = open(id, Access.READ)) {
                                            return true;
                                        } catch (RuntimeException unreadable) {
                                            return false;
                                        }
                                    })
                            .sorted(Comparator.naturalOrder())
                            .toList();
            return List.copyOf(ids);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not list sessions under " + root, failure);
        }
    }

    /** Read one stored session's metadata without loading its events. */
    public Stats stat(String sessionId) {
        requireSafeId(sessionId);
        Path logFile = logPath(sessionId);
        if (!Files.isRegularFile(logFile)) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        List<String> lines = readLines(logFile);
        if (lines.isEmpty()) {
            throw new SessionCorruptionException("Session log is empty: " + sessionId);
        }
        int version = versionOf(lines.get(0), sessionId);
        long size;
        try {
            size = Files.size(logFile);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not stat " + logFile, failure);
        }
        return new Stats(sessionId, version, Math.max(0, lines.size() - 1), size);
    }

    /** The log file path for a session, exposed so tooling and tests can inspect it. */
    public Path logPath(String sessionId) {
        return directoryOf(sessionId).resolve(LOG_FILE);
    }

    /**
     * One open session.
     *
     * <p>A write handle appends and flushes; a read handle only reads. {@link #close()} is idempotent
     * and releases write ownership.
     */
    public final class Handle implements AutoCloseable {

        private final String sessionId;
        private final SessionLog log;
        private final boolean writable;
        private final Path logFile;
        private boolean closed;

        private Handle(String sessionId, SessionLog log, boolean writable, Path logFile) {
            this.sessionId = sessionId;
            this.log = log;
            this.writable = writable;
            this.logFile = logFile;
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
         * @throws SessionReadOnlyException when the handle was opened for reading
         */
        public void append(long time, SessionEventData.SurfaceData data) {
            requireOpen();
            requireWritable();
            append(0, time, data);
        }

        /**
         * Append one message-producing event at a specific position and persist it.
         *
         * @param expectedSeq the position the caller expects, or zero to accept the next position
         * @param time Unix epoch milliseconds
         * @param data the message-producing payload
         */
        public void append(long expectedSeq, long time, SessionEventData.SurfaceData data) {
            requireOpen();
            requireWritable();
            if (expectedSeq != 0 && expectedSeq != log.size()) {
                throw new IllegalArgumentException(
                        "Expected seq " + expectedSeq + " but the log holds " + log.size());
            }
            writeEvent(
                    SessionEvent.surface(log.nextSeq(), time, data, SurfaceOp.append(), List.of()));
        }

        /**
         * Append a message-producing event that replaces an existing surface range and persist it.
         *
         * <p>This is the path a compaction takes: the shadowed events stay in the log, and the
         * replacing node cites every node it hides so a reader can trace what it displaced.
         *
         * @param time Unix epoch milliseconds
         * @param data the message-producing payload
         * @param operation how the event enters the surface
         * @param sourceEventSeqs the shadowed nodes, which the fold validates for completeness
         */
        public void append(
                long time,
                SessionEventData.SurfaceData data,
                SurfaceOp operation,
                List<SessionSeq.Seq> sourceEventSeqs) {
            requireOpen();
            requireWritable();
            writeEvent(
                    SessionEvent.surface(
                            log.nextSeq(),
                            time,
                            data,
                            operation,
                            sourceEventSeqs == null ? List.of() : sourceEventSeqs));
        }

        /**
         * Commit an event that has already been validated against this session's surface.
         *
         * <p>A caller assembling a batch validates every member before any of it lands, then commits
         * the accepted prefix here. Appending to the handle's own log view without this call would
         * record the event in memory only and lose it on reopen.
         *
         * @param event the validated event, whose position must be the next one
         */
        public void commitValidated(SessionEvent event) {
            requireOpen();
            requireWritable();
            writeEvent(event);
        }

        /**
         * Append a log-only event and persist it.
         *
         * @param time Unix epoch milliseconds
         * @param data the log-only payload
         */
        public void appendLogOnly(long time, SessionEventData data) {
            requireOpen();
            requireWritable();
            writeEvent(SessionEvent.logOnly(log.nextSeq(), time, data));
        }

        /**
         * Durability barrier: every committed append is on disk when this returns.
         */
        public void flush() {
            requireOpen();
            requireWritable();
            // Each append already writes through and syncs, so a flush has nothing left to drain.
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
            log.append(event);
            try {
                Files.writeString(
                        logFile,
                        encodeEvent(event) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not append to " + logFile, failure);
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new SessionHandleClosedException("Session handle is closed: " + sessionId);
            }
        }

        private void requireWritable() {
            if (!writable) {
                throw new SessionReadOnlyException("Session handle is read-only: " + sessionId);
            }
        }
    }

    private String headerLine() {
        return "{\"version\":" + SessionFormatVersion.CURRENT + "}";
    }

    private int versionOf(String headerLine, String sessionId) {
        try {
            Map<?, ?> header = mapper.readValue(headerLine, Map.class);
            Object version = header.get("version");
            if (version instanceof Number number) {
                return number.intValue();
            }
            throw new SessionCorruptionException("Session header has no version: " + sessionId);
        } catch (IOException failure) {
            throw new SessionCorruptionException("Session header is not readable: " + sessionId);
        }
    }

    private List<String> readLines(Path logFile) {
        try {
            List<String> lines =
                    new ArrayList<>(Files.readAllLines(logFile, StandardCharsets.UTF_8));
            // A torn final line is an append that never completed, so it is dropped rather than
            // parsed. A complete record is never dropped.
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).endsWith("}")) {
                lines.remove(lines.size() - 1);
            }
            lines.removeIf(String::isBlank);
            return lines;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read " + logFile, failure);
        }
    }

    private String encodeEvent(SessionEvent event) {
        try {
            return mapper.writeValueAsString(SessionEventCodec.encode(event, mapper));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not encode a session event", failure);
        }
    }

    private SessionEvent decodeEvent(String line, String sessionId, int expectedSeq) {
        try {
            return SessionEventCodec.decode(mapper.readValue(line, Map.class), mapper, expectedSeq);
        } catch (IOException failure) {
            throw new SessionCorruptionException(
                    "Session " + sessionId + " has an unreadable record at " + expectedSeq);
        }
    }

    private Path directoryOf(String sessionId) {
        return root.resolve(sessionId);
    }

    /** Reject an id that is not a single safe path segment. */
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

    /** A mutation was attempted on a read handle. */
    public static class SessionReadOnlyException extends RuntimeException {
        public SessionReadOnlyException(String message) {
            super(message);
        }
    }

    /** The handle was closed. */
    public static class SessionHandleClosedException extends RuntimeException {
        public SessionHandleClosedException(String message) {
            super(message);
        }
    }

    /** The stored log is damaged. */
    public static class SessionCorruptionException extends RuntimeException {
        public SessionCorruptionException(String message) {
            super(message);
        }
    }

    /** The stored format version is not readable by this build. */
    public static class FormatUnsupportedException extends RuntimeException {
        public FormatUnsupportedException(String message) {
            super(message);
        }
    }
}
