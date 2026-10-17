/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Append-only event timeline and deliverable archive for advisor sessions.
 *
 * <p>Every report action taken by the main agent lands here first, then reaches the visitor over
 * the AG-UI stream — so a page refresh can rebuild the whole process view by replaying the
 * timeline (F11). Turns count visitor messages; sequence numbers are monotonic per session.
 *
 * <p><b>This is an application-layer projection, not a session source of truth.</b> The advisor
 * agent's authoritative conversation record is the kernel session log
 * ({@code io.deepscope.core.session.log}); this table exists so the console can replay its own
 * presentation cards — routes, subagent dispatches, proposals, deliverables — without replaying the
 * agent. The two stores are deliberately not merged: this one owns the console's view model and its
 * kinds are a contract with the frontend renderer, while resume and history reconstruction read the
 * kernel log. Keep new conversation state out of here.
 */
public final class TimelineStore {

    /** Event kind names — shared contract with the frontend card renderer. */
    public static final String USER_MESSAGE = "user_message";

    public static final String ADVISOR_MESSAGE = "advisor_message";
    public static final String ROUTE = "route";
    public static final String LOAD_SKILL = "load_skill";
    public static final String DECOMPOSE = "decompose";
    public static final String DISPATCH = "dispatch";
    public static final String SELF_TASK = "self_task";
    public static final String SUBAGENT_FACTS = "subagent_facts";
    public static final String SUBAGENT_SETTLED = "subagent_settled";

    /** One step of a subagent's own process, correlated back to its dispatch by {@code subtaskId}. */
    public static final String SUBAGENT_STEP = "subagent_step";

    public static final String PROPOSAL = "proposal";
    public static final String PROPOSAL_RESOLVED = "proposal_resolved";
    public static final String PERMISSION = "permission";
    public static final String PERMISSION_RESOLVED = "permission_resolved";
    public static final String ADVISOR_NOTE = "advisor_note";
    public static final String QUESTION = "question";
    public static final String DELIVERABLE = "deliverable";
    public static final String ERROR = "error";
    public static final String TOOL_CALL = "tool_call";
    public static final String USAGE = "usage";
    public static final String KNOWLEDGE = "knowledge";
    public static final String COMPACTION = "compaction";

    /** One timeline entry; payload stays a raw JSON string exactly as stored. */
    public record TimelineEntry(int seq, int turn, String kind, String payload, long createdAt) {}

    /** A persisted deliverable; body is the full JSON document of the deliverable card. */
    public record DeliverableRow(int version, String title, String body, long createdAt) {}

    /** Result of an append: what the AG-UI event carries. */
    public record AppendedEvent(int seq, int turn, String kind, Map<String, Object> payload) {}

    private final DataSource dataSource;
    private final SessionStore sessions;
    private final ObjectMapper json;

    public TimelineStore(DataSource dataSource, SessionStore sessions, ObjectMapper json) {
        this.dataSource = dataSource;
        this.sessions = sessions;
        this.json = json;
    }

    /**
     * Appends one event. A visitor message starts a new turn and marks the session running; a
     * deliverable ends it as idle, a question or a subagent proposal parks it in awaiting_visitor,
     * an error fails it.
     */
    public synchronized AppendedEvent append(
            String sessionId, String kind, Map<String, Object> payload) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSession(connection, sessionId);
                int turn = nextTurn(connection, sessionId, USER_MESSAGE.equals(kind));
                int seq = nextSeq(connection, sessionId);
                long now = System.currentTimeMillis();
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_events (session_id, seq, turn, type,"
                                        + " payload, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, sessionId);
                    statement.setInt(2, seq);
                    statement.setInt(3, turn);
                    statement.setString(4, kind);
                    statement.setString(5, writeJson(payload));
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }
                String status =
                        !USER_MESSAGE.equals(kind)
                                        && (pendingQuestion(connection, sessionId).isPresent()
                                                || (ADVISOR_MESSAGE.equals(kind)
                                                        && hasPendingProposals(
                                                                connection, sessionId)))
                                ? SessionStore.STATUS_AWAITING_VISITOR
                                : statusAfter(kind);
                updateStatus(connection, sessionId, status);
                connection.commit();
                return new AppendedEvent(seq, turn, kind, payload);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** One accepted answer; replayed answers must not unconditionally start another model run. */
    public record AnsweredEvent(AppendedEvent event, boolean replayed) {}

    /** Serializes question creation with answers using the same session row lock. */
    public AppendedEvent appendQuestion(String sessionId, Map<String, Object> payload)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSession(connection, sessionId);
                var pending = pendingQuestion(connection, sessionId);
                if (pending.isPresent()) {
                    if (!QuestionService.sameQuestion(pending.get().payload(), payload))
                        throw QuestionService.conflict("question_pending");
                    connection.commit();
                    return pending.get();
                }
                int seq = nextSeq(connection, sessionId);
                int turn = nextTurn(connection, sessionId, false);
                insertEvent(connection, sessionId, seq, turn, QUESTION, payload);
                updateStatus(connection, sessionId, SessionStore.STATUS_AWAITING_VISITOR);
                connection.commit();
                return new AppendedEvent(seq, turn, QUESTION, payload);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Validates and saves the answer, event and status in a single database transaction. */
    public AnsweredEvent appendQuestionReply(
            String sessionId,
            String owner,
            String text,
            Object attachments,
            Object reply,
            boolean running)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockOwnedSession(connection, sessionId, owner);
                var pending = pendingQuestion(connection, sessionId);
                Map<String, Object> payload;
                if (reply != null) {
                    String id = QuestionService.questionId(reply);
                    AppendedEvent question =
                            findQuestion(connection, sessionId, id)
                                    .orElseThrow(QuestionService::notFound);
                    payload = QuestionService.answer(question.payload(), reply, text);
                    var answered = firstAnswer(connection, sessionId, question);
                    if (answered.isPresent()) {
                        Object savedReply = answered.get().payload().get("questionReply");
                        Object newReply = payload.get("questionReply");
                        if (savedReply instanceof Map<?, ?> saved
                                && newReply instanceof Map<?, ?> incoming
                                && saved.get("requestId").equals(incoming.get("requestId"))) {
                            if (!QuestionService.sameAnswer(answered.get().payload(), payload))
                                throw QuestionService.conflict("question_request_conflict");
                            connection.commit();
                            return new AnsweredEvent(answered.get(), true);
                        }
                        throw QuestionService.conflict("question_already_answered");
                    }
                    if (pending.isEmpty() || !id.equals(pending.get().payload().get("questionId")))
                        throw QuestionService.notFound();
                } else if (pending.isPresent()) {
                    payload = QuestionService.answer(pending.get().payload(), null, text);
                } else {
                    if (text == null || text.isBlank()) throw QuestionService.invalid();
                    payload = Map.of("text", text);
                }
                if (running) throw QuestionService.conflict("session_running");
                payload = new LinkedHashMap<>(payload);
                if (attachments instanceof List<?> list && !list.isEmpty())
                    payload.put("attachments", list);
                int seq = nextSeq(connection, sessionId);
                int turn = nextTurn(connection, sessionId, true);
                insertEvent(connection, sessionId, seq, turn, USER_MESSAGE, payload);
                updateStatus(connection, sessionId, SessionStore.STATUS_RUNNING);
                connection.commit();
                return new AnsweredEvent(
                        new AppendedEvent(seq, turn, USER_MESSAGE, payload), false);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<AppendedEvent> pendingQuestion(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return pendingQuestion(connection, sessionId);
        }
    }

    private Optional<AppendedEvent> pendingQuestion(Connection connection, String sessionId)
            throws SQLException {
        var question = latestQuestion(connection, sessionId);
        return question.isPresent() && firstAnswer(connection, sessionId, question.get()).isEmpty()
                ? question
                : Optional.empty();
    }

    private Optional<AppendedEvent> latestQuestion(Connection connection, String sessionId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT seq, turn, payload FROM advisor_events WHERE session_id = ? AND"
                                + " type = ? ORDER BY seq DESC LIMIT 1")) {
            statement.setString(1, sessionId);
            statement.setString(2, QUESTION);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(questionRow(rows)) : Optional.empty();
            }
        }
    }

    private AppendedEvent questionRow(ResultSet rows) throws SQLException {
        int seq = rows.getInt("seq");
        int turn = rows.getInt("turn");
        return new AppendedEvent(
                seq,
                turn,
                QUESTION,
                QuestionService.project(readPayload(rows.getString("payload")), seq, turn));
    }

    private Optional<AppendedEvent> findQuestion(Connection connection, String sessionId, String id)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT seq, turn, payload FROM advisor_events WHERE session_id = ? AND"
                                + " type = ? ORDER BY seq DESC")) {
            statement.setString(1, sessionId);
            statement.setString(2, QUESTION);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    var question = questionRow(rows);
                    if (id.equals(question.payload().get("questionId")))
                        return Optional.of(question);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<AppendedEvent> firstAnswer(
            Connection connection, String sessionId, AppendedEvent question) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT seq, turn, payload FROM advisor_events WHERE session_id = ? AND seq"
                                + " > ? AND type = ? ORDER BY seq")) {
            statement.setString(1, sessionId);
            statement.setInt(2, question.seq());
            statement.setString(3, USER_MESSAGE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> value = readPayload(rows.getString("payload"));
                    if (!(value.get("questionReply") instanceof Map<?, ?> reply)
                            || question.payload().get("questionId").equals(reply.get("questionId")))
                        return Optional.of(
                                new AppendedEvent(
                                        rows.getInt("seq"),
                                        rows.getInt("turn"),
                                        USER_MESSAGE,
                                        value));
                }
            }
        }
        return Optional.empty();
    }

    /** Latest saved answer with no subsequent advisor output on its turn, for explicit continuation. */
    public Optional<AppendedEvent> pendingQuestionReply(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT seq, turn, payload FROM advisor_events WHERE session_id = ?"
                                        + " AND type = ? ORDER BY seq DESC LIMIT 1")) {
            statement.setString(1, sessionId);
            statement.setString(2, USER_MESSAGE);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                int seq = rows.getInt("seq");
                int turn = rows.getInt("turn");
                Map<String, Object> value = readPayload(rows.getString("payload"));
                if (!value.containsKey("questionReply")) return Optional.empty();
                try (PreparedStatement output =
                        connection.prepareStatement(
                                "SELECT 1 FROM advisor_events WHERE session_id = ? AND turn = ? AND"
                                        + " seq > ? AND type <> ? LIMIT 1")) {
                    output.setString(1, sessionId);
                    output.setInt(2, turn);
                    output.setInt(3, seq);
                    output.setString(4, "agent_route");
                    try (ResultSet after = output.executeQuery()) {
                        if (after.next()) return Optional.empty();
                    }
                }
                return Optional.of(new AppendedEvent(seq, turn, USER_MESSAGE, value));
            }
        }
    }

    private static void lockOwnedSession(Connection connection, String sessionId, String owner)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT owner_user_id FROM advisor_sessions WHERE session_id = ? FOR"
                                + " UPDATE")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !owner.equals(rows.getString(1)))
                    throw QuestionService.notFound();
            }
        }
    }

    private static void updateStatus(Connection connection, String sessionId, String status)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE advisor_sessions SET status = ?, updated_at = ? WHERE session_id ="
                                + " ?")) {
            statement.setString(1, status);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
        }
    }

    private boolean hasPendingProposals(Connection connection, String sessionId)
            throws SQLException {
        List<String> pending = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT type, payload FROM advisor_events WHERE session_id = ? AND type IN"
                                + " (?, ?) ORDER BY seq")) {
            statement.setString(1, sessionId);
            statement.setString(2, PROPOSAL);
            statement.setString(3, PROPOSAL_RESOLVED);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name =
                            String.valueOf(readPayload(rows.getString("payload")).get("name"));
                    if (PROPOSAL.equals(rows.getString("type"))) pending.add(name);
                    else pending.remove(name);
                }
            }
        }
        return !pending.isEmpty();
    }

    /** Appends a visitor message and returns its timeline entry. */
    public AppendedEvent appendVisitorMessage(String sessionId, String text) throws SQLException {
        return appendVisitorMessage(sessionId, text, null);
    }

    /** Appends a visitor message with optional attachment metadata and returns its entry. */
    public AppendedEvent appendVisitorMessage(String sessionId, String text, Object attachments)
            throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text == null ? "" : text);
        if (attachments instanceof List<?> list && !list.isEmpty()) {
            payload.put("attachments", list);
        }
        return append(sessionId, USER_MESSAGE, payload);
    }

    /**
     * Appends an advisor reply bubble recorded by the frontend after a run completes. The
     * optional reasoning text is the model's streamed thinking for this turn — kept in the
     * payload so a page refresh or session switch can replay the thinking card (F11).
     */
    public AppendedEvent appendAdvisorMessage(
            String sessionId, String text, String reasoning, String mode, String domainName)
            throws SQLException {
        return appendAdvisorMessage(sessionId, text, reasoning, mode, domainName, null);
    }

    public AppendedEvent appendAdvisorMessage(
            String sessionId,
            String text,
            String reasoning,
            String mode,
            String domainName,
            List<Map<String, Object>> parts)
            throws SQLException {
        return appendAdvisorMessage(sessionId, text, reasoning, mode, domainName, parts, null);
    }

    public AppendedEvent appendAdvisorMessage(
            String sessionId,
            String text,
            String reasoning,
            String mode,
            String domainName,
            List<Map<String, Object>> parts,
            Map<String, Object> presentation)
            throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text == null ? "" : text);
        if (reasoning != null && !reasoning.isBlank()) {
            payload.put("reasoning", reasoning);
        }
        payload.put("mode", mode == null ? "" : mode);
        payload.put("domainName", domainName == null ? "" : domainName);
        if (parts != null) {
            payload.put("partsVersion", 1);
            payload.put("parts", parts);
        }
        if (presentation != null) {
            payload.put("presentationVersion", 1);
            payload.put("presentation", presentation);
        }
        return append(sessionId, ADVISOR_MESSAGE, payload);
    }

    /**
     * Writes settled tool calls and per-step usage onto the current turn, before the advisor
     * reply, so a refresh can replay the same process group.
     */
    public void appendRunArtifacts(String sessionId, Object toolCalls, Object usage)
            throws SQLException {
        appendRunArtifacts(sessionId, toolCalls, usage, null);
    }

    public AppendedEvent saveAdvisorRound(
            String sessionId,
            Map<String, Object> payload,
            Object toolCalls,
            Object usage,
            Object subagentRuns,
            int recordedTurn)
            throws SQLException {
        String runId = runId(payload);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSession(connection, sessionId);
                Optional<AppendedEvent> existing = findAdvisorRun(connection, sessionId, runId);
                if (existing.isPresent()) {
                    Map<String, Object> before = existing.get().payload();
                    if (!java.util.Objects.equals(before.get("text"), payload.get("text"))
                            || !java.util.Objects.equals(
                                    before.getOrDefault("reasoning", ""),
                                    payload.getOrDefault("reasoning", ""))) {
                        throw new IllegalArgumentException("Conflicting content for saved run");
                    }
                }
                int latestTurn = nextTurn(connection, sessionId, false);
                int turn =
                        existing.map(AppendedEvent::turn)
                                .orElse(recordedTurn > 0 ? recordedTurn : latestTurn);
                java.util.Set<String> recorded = new java.util.HashSet<>();
                if (runId != null) {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT type, payload FROM advisor_events WHERE session_id = ?"
                                            + " AND turn = ?")) {
                        statement.setString(1, sessionId);
                        statement.setInt(2, turn);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                Map<String, Object> before = readPayload(rows.getString("payload"));
                                if (runId.equals(before.get("runId")))
                                    recorded.add(artifactKey(rows.getString("type"), before));
                            }
                        }
                    }
                }
                int seq = nextSeq(connection, sessionId);
                for (RoundArtifact artifact : artifacts(toolCalls, usage, subagentRuns)) {
                    Map<String, Object> value = new LinkedHashMap<>(artifact.payload());
                    String key = artifactKey(artifact.kind(), value);
                    if (runId != null && !recorded.add(key)) continue;
                    if (runId != null) value.put("runId", runId);
                    insertEvent(connection, sessionId, seq++, turn, artifact.kind(), value);
                }
                AppendedEvent saved;
                if (existing.isPresent()) saved = existing.get();
                else {
                    insertEvent(connection, sessionId, seq, turn, ADVISOR_MESSAGE, payload);
                    saved = new AppendedEvent(seq, turn, ADVISOR_MESSAGE, payload);
                    if (turn == latestTurn) {
                        String outcome =
                                payload.get("presentation") instanceof Map<?, ?> presentation
                                        ? String.valueOf(presentation.get("outcome"))
                                        : "completed";
                        String status =
                                "failed".equals(outcome)
                                        ? SessionStore.STATUS_FAILED
                                        : "cancelled".equals(outcome) || "unknown".equals(outcome)
                                                ? SessionStore.STATUS_INTERRUPTED
                                                : "awaiting_action".equals(outcome)
                                                                || pendingQuestion(
                                                                                connection,
                                                                                sessionId)
                                                                        .isPresent()
                                                                || hasPendingProposals(
                                                                        connection, sessionId)
                                                        ? SessionStore.STATUS_AWAITING_VISITOR
                                                        : SessionStore.STATUS_IDLE;
                        try (PreparedStatement statement =
                                connection.prepareStatement(
                                        "UPDATE advisor_sessions SET status = ?, updated_at = ?"
                                                + " WHERE session_id = ?")) {
                            statement.setString(1, status);
                            statement.setLong(2, System.currentTimeMillis());
                            statement.setString(3, sessionId);
                            statement.executeUpdate();
                        }
                    }
                }
                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<AppendedEvent> findAdvisorRun(String sessionId, String runId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findAdvisorRun(connection, sessionId, runId);
        }
    }

    private Optional<AppendedEvent> findAdvisorRun(
            Connection connection, String sessionId, String runId) throws SQLException {
        if (runId == null) return Optional.empty();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT seq, turn, payload FROM advisor_events WHERE session_id = ? AND"
                                + " type = ? ORDER BY seq DESC")) {
            statement.setString(1, sessionId);
            statement.setString(2, ADVISOR_MESSAGE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> value = readPayload(rows.getString("payload"));
                    if (runId.equals(runId(value)))
                        return Optional.of(
                                new AppendedEvent(
                                        rows.getInt("seq"),
                                        rows.getInt("turn"),
                                        ADVISOR_MESSAGE,
                                        value));
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> readPayload(String value) throws SQLException {
        try {
            return json.readValue(
                    value,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            throw new SQLException("Invalid saved timeline payload", error);
        }
    }

    private static String runId(Map<String, Object> value) {
        if (value.get("presentation") instanceof Map<?, ?> presentation
                && presentation.get("runId") instanceof String run) return run;
        if (value.get("parts") instanceof List<?> parts
                && !parts.isEmpty()
                && parts.get(0) instanceof Map<?, ?> part
                && part.get("runId") instanceof String run) return run;
        return null;
    }

    private record RoundArtifact(String kind, Map<String, Object> payload) {}

    private static List<RoundArtifact> artifacts(Object tools, Object usage, Object runs) {
        List<RoundArtifact> result = new ArrayList<>();
        addArtifacts(result, TOOL_CALL, tools);
        addArtifacts(result, USAGE, usage);
        if (runs instanceof List<?> list)
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> run)
                        || !(run.get("subtaskId") instanceof String id)
                        || !(run.get("events") instanceof List<?> steps)) continue;
                int order = 0;
                for (Object step : steps)
                    if (step instanceof Map<?, ?> raw) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        raw.forEach(
                                (key, value) -> {
                                    if (key != null) payload.put(key.toString(), value);
                                });
                        payload.put("subtaskId", id);
                        payload.put("order", ++order);
                        result.add(new RoundArtifact(SUBAGENT_STEP, payload));
                    }
            }
        return result;
    }

    private static void addArtifacts(List<RoundArtifact> out, String kind, Object items) {
        if (items instanceof List<?> list)
            for (Object item : list)
                if (item instanceof Map<?, ?> raw && !raw.isEmpty()) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    raw.forEach(
                            (key, value) -> {
                                if (key != null) payload.put(key.toString(), value);
                            });
                    out.add(new RoundArtifact(kind, payload));
                }
    }

    private static String artifactKey(String kind, Map<String, Object> value) {
        return kind
                + ":"
                + (TOOL_CALL.equals(kind)
                        ? value.get("id")
                        : USAGE.equals(kind)
                                ? value.get("stepId")
                                : value.get("subtaskId") + ":" + value.get("order"));
    }

    private static void lockSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT session_id FROM advisor_sessions WHERE session_id = ? FOR"
                                + " UPDATE")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Session no longer exists");
            }
        }
    }

    private void insertEvent(
            Connection connection,
            String sessionId,
            int seq,
            int turn,
            String kind,
            Map<String, Object> payload)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO advisor_events (session_id, seq, turn, type, payload,"
                                + " created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, sessionId);
            statement.setInt(2, seq);
            statement.setInt(3, turn);
            statement.setString(4, kind);
            statement.setString(5, writeJson(payload));
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /**
     * Writes the whole process of one round: settled tool calls, per-step usage, and each
     * subagent's own steps. Subagent steps keep their arrival order through the {@code order}
     * field carried inside the payload; {@code created_at} stays the server append time.
     */
    public void appendRunArtifacts(
            String sessionId, Object toolCalls, Object usage, Object subagentRuns)
            throws SQLException {
        appendMaps(sessionId, TOOL_CALL, toolCalls);
        appendMaps(sessionId, USAGE, usage);
        appendSubagentSteps(sessionId, subagentRuns);
    }

    /** Flattens {@code [{subtaskId, events: [...]}]} into one ordered row per step. */
    private void appendSubagentSteps(String sessionId, Object runs) throws SQLException {
        if (!(runs instanceof List<?> list)) return;
        for (Object run : list) {
            if (!(run instanceof Map<?, ?> map)) continue;
            Object subtaskId = map.get("subtaskId");
            if (subtaskId == null || subtaskId.toString().isBlank()) continue;
            Object steps = map.get("events");
            if (!(steps instanceof List<?> events)) continue;
            int order = 0;
            for (Object step : events) {
                if (!(step instanceof Map<?, ?> stepMap) || stepMap.isEmpty()) continue;
                order += 1;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("subtaskId", subtaskId.toString());
                payload.put("order", order);
                for (Map.Entry<?, ?> entry : stepMap.entrySet()) {
                    if (entry.getKey() != null) {
                        payload.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                append(sessionId, SUBAGENT_STEP, payload);
            }
        }
    }

    private void appendMaps(String sessionId, String kind, Object items) throws SQLException {
        if (!(items instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || map.isEmpty()) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null)
                    payload.put(entry.getKey().toString(), entry.getValue());
            }
            append(sessionId, kind, payload);
        }
    }

    /** Persists a deliverable with the next version number and returns it. */
    public int saveDeliverable(String sessionId, String title, Map<String, Object> body)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement next =
                        connection.prepareStatement(
                                "SELECT COALESCE(MAX(version), 0) + 1 FROM advisor_deliverables"
                                        + " WHERE session_id = ?")) {
            next.setString(1, sessionId);
            int version;
            try (ResultSet rows = next.executeQuery()) {
                rows.next();
                version = rows.getInt(1);
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_deliverables (session_id, version, title, body,"
                                    + " created_at) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, sessionId);
                statement.setInt(2, version);
                statement.setString(3, title);
                statement.setString(4, writeJson(body));
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return version;
        }
    }

    /** Full timeline of a session, ordered by seq (the replay source for F11). */
    public List<TimelineEntry> listEntries(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT seq, turn, type, payload, created_at FROM"
                                        + " advisor_events WHERE session_id = ? ORDER BY seq")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                List<TimelineEntry> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(
                            new TimelineEntry(
                                    rows.getInt("seq"),
                                    rows.getInt("turn"),
                                    rows.getString("type"),
                                    rows.getString("payload"),
                                    rows.getLong("created_at")));
                }
                return entries;
            }
        }
    }

    /**
     * Clears all timeline entries and deliverables for a session, resetting its status to idle.
     */
    public synchronized void clearEntries(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_events WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_deliverables WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
        sessions.updateStatus(sessionId, SessionStore.STATUS_IDLE);
    }

    /** Unresolved proposals in timeline order; same-name resolutions consume the oldest one. */
    public List<TimelineEntry> pendingProposals(String sessionId) throws SQLException {
        List<TimelineEntry> pending = new ArrayList<>();
        try {
            for (var entry : listEntries(sessionId)) {
                if (PROPOSAL.equals(entry.kind())) pending.add(entry);
                if (PROPOSAL_RESOLVED.equals(entry.kind())) {
                    String name = json.readTree(entry.payload()).path("name").asText("");
                    for (var proposal : List.copyOf(pending)) {
                        if (name.equals(
                                json.readTree(proposal.payload()).path("name").asText(""))) {
                            pending.remove(proposal);
                            break;
                        }
                    }
                }
            }
            return pending;
        } catch (JsonProcessingException error) {
            throw new SQLException("Invalid proposal event payload", error);
        }
    }

    public List<DeliverableRow> listDeliverables(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT version, title, body, created_at FROM"
                                        + " advisor_deliverables WHERE session_id = ? ORDER BY"
                                        + " version")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                List<DeliverableRow> deliverables = new ArrayList<>();
                while (rows.next()) {
                    deliverables.add(
                            new DeliverableRow(
                                    rows.getInt("version"),
                                    rows.getString("title"),
                                    rows.getString("body"),
                                    rows.getLong("created_at")));
                }
                return deliverables;
            }
        }
    }

    /** The latest deliverable of a session, used for version bumps on follow-up turns (F10). */
    public Optional<DeliverableRow> latestDeliverable(String sessionId) throws SQLException {
        List<DeliverableRow> deliverables = listDeliverables(sessionId);
        return deliverables.isEmpty()
                ? Optional.empty()
                : Optional.of(deliverables.get(deliverables.size() - 1));
    }

    private static String statusAfter(String kind) {
        return switch (kind) {
            case USER_MESSAGE -> SessionStore.STATUS_RUNNING;
            case ADVISOR_MESSAGE, DELIVERABLE, COMPACTION -> SessionStore.STATUS_IDLE;
            case QUESTION, PROPOSAL -> SessionStore.STATUS_AWAITING_VISITOR;
            case ERROR -> SessionStore.STATUS_FAILED;
            default -> SessionStore.STATUS_RUNNING;
        };
    }

    private static int nextSeq(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COALESCE(MAX(seq), 0) + 1 FROM advisor_events WHERE session_id"
                                + " = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int nextTurn(Connection connection, String sessionId, boolean startsNewTurn)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COALESCE(MAX(turn), 0) FROM advisor_events WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                int current = rows.getInt(1);
                return startsNewTurn ? current + 1 : Math.max(current, 1);
            }
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize timeline payload", error);
        }
    }
}
