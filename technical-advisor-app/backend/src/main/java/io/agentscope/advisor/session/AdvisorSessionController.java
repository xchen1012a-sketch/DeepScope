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
package io.agentscope.advisor.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.AdvisorProperties;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Session lifecycle API for the chat page. Every resource is scoped to the logged-in account;
 * sessions of other visitors are indistinguishable from deleted ones (404).
 *
 * <p>The conversation itself runs over the framework AG-UI endpoint with the session id as
 * thread id — this controller owns the sidebar and the persisted timeline that powers refresh
 * recovery (F11).
 */
@RestController
@RequestMapping("/api/advisor/sessions")
public class AdvisorSessionController {

    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final SessionTitleService titleService;
    private final ObjectMapper json;
    private final Path workspaceDir;
    private final AguiRoundPersister roundPersister;
    private final SessionCompactionService compactionService;
    private final io.agentscope.spring.boot.agui.mvc.AguiMvcController aguiMvcController;
    private final io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator;

    public AdvisorSessionController(
            SessionStore sessions,
            TimelineStore timeline,
            SessionTitleService titleService,
            ObjectMapper json) {
        this(
                sessions,
                timeline,
                titleService,
                json,
                new AdvisorProperties(
                        Path.of(System.getProperty("java.io.tmpdir"), "advisor-test-data")),
                null,
                null,
                null,
                null);
    }

    public AdvisorSessionController(
            SessionStore sessions,
            TimelineStore timeline,
            SessionTitleService titleService,
            ObjectMapper json,
            AdvisorProperties properties,
            AguiRoundPersister roundPersister,
            SessionCompactionService compactionService) {
        this(
                sessions,
                timeline,
                titleService,
                json,
                properties,
                roundPersister,
                compactionService,
                null,
                null);
    }

    @Autowired
    public AdvisorSessionController(
            SessionStore sessions,
            TimelineStore timeline,
            SessionTitleService titleService,
            ObjectMapper json,
            AdvisorProperties properties,
            AguiRoundPersister roundPersister,
            SessionCompactionService compactionService,
            @Autowired(required = false)
                    io.agentscope.spring.boot.agui.mvc.AguiMvcController aguiMvcController,
            @Autowired(required = false)
                    io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator) {
        this.sessions = sessions;
        this.timeline = timeline;
        this.titleService = titleService;
        this.json = json;
        this.workspaceDir = properties.workspaceDir();
        this.roundPersister = roundPersister;
        this.compactionService = compactionService;
        this.aguiMvcController = aguiMvcController;
        this.toolRuntimeCoordinator = toolRuntimeCoordinator;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) throws SQLException {
        String owner = owner(request);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionStore.SessionRow session : sessions.list(owner)) {
            out.add(sessionView(session));
        }
        return out;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request)
            throws SQLException {
        String title = body == null ? null : (String) body.get("title");
        String agentId = body == null ? null : text(body, "agentId");
        return sessionView(sessions.create(owner(request), title, agentId));
    }

    /**
     * Parses a visitor-uploaded attachment (image, ZIP archive, Word/PPT/Excel, PDF, Markdown, or
     * code/text file) so the start page and chat composer can attach context before or during a
     * session.
     */
    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAttachment(
            @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        owner(request);
        try {
            Map<String, Object> parsed = SessionAttachments.process(file, workspaceDir);
            return ResponseEntity.status(HttpStatus.CREATED).body(parsed);
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().body(Map.of("message", bad.getMessage()));
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(
                            Map.of(
                                    "message",
                                    error.getMessage() == null ? "附件解析失败" : error.getMessage()));
        }
    }

    @PostMapping(path = "/attachments/by-path")
    public ResponseEntity<Map<String, Object>> uploadAttachmentByPath(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        owner(request);
        String rawPath = body == null ? null : body.get("path");
        if (rawPath == null || rawPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件路径不能为空"));
        }
        try {
            Map<String, Object> parsed = SessionAttachments.processLocalPath(rawPath, workspaceDir);
            return ResponseEntity.status(HttpStatus.CREATED).body(parsed);
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().body(Map.of("message", bad.getMessage()));
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(
                            Map.of(
                                    "message",
                                    error.getMessage() == null ? "附件解析失败" : error.getMessage()));
        }
    }

    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> attachmentContent(
            @PathVariable String attachmentId, HttpServletRequest request) throws Exception {
        owner(request);
        var content = SessionAttachments.readAttachmentContent(attachmentId, workspaceDir);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var found = content.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(found.mimeType()))
                .header("Cache-Control", "private, max-age=86400")
                .body(found.bytes());
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> rename(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return sessions.rename(sessionId, account.userId(), body.get("title"))
                ? ResponseEntity.ok(sessionView(sessions.find(sessionId).orElseThrow()))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return sessions.delete(sessionId, account.userId())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Full replay source for the conversation view: session, timeline and deliverable archive. */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String sessionId, HttpServletRequest request) throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (TimelineStore.TimelineEntry entry : timeline.listEntries(sessionId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("seq", entry.seq());
            view.put("turn", entry.turn());
            view.put("kind", entry.kind());
            view.put("payload", parseJson(entry.payload()));
            view.put("createdAt", entry.createdAt());
            entries.add(view);
        }
        List<Map<String, Object>> deliverables = new ArrayList<>();
        for (TimelineStore.DeliverableRow deliverable : timeline.listDeliverables(sessionId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("version", deliverable.version());
            view.put("title", deliverable.title());
            view.put("body", parseJson(deliverable.body()));
            view.put("createdAt", deliverable.createdAt());
            deliverables.add(view);
        }
        Map<String, Object> detail =
                new LinkedHashMap<>(
                        Map.of(
                                "session",
                                sessionView(session),
                                "runtime",
                                aguiMvcController == null
                                        ? Map.of("phase", "idle", "interrupts", List.of())
                                        : aguiMvcController.runtimeSnapshot(sessionId),
                                "entries",
                                entries,
                                "deliverables",
                                deliverables));
        detail.put(
                "pendingQuestion",
                timeline.pendingQuestion(sessionId)
                        .map(TimelineStore.AppendedEvent::payload)
                        .orElse(null));
        detail.put(
                "pendingQuestionReply",
                timeline.pendingQuestionReply(sessionId).map(this::eventView).orElse(null));
        return ResponseEntity.ok(detail);
    }

    /** Paginated timeline cards for the execution process drawer's Timeline tab. */
    @GetMapping("/{sessionId}/timeline")
    public ResponseEntity<Map<String, Object>> timelinePage(
            @PathVariable String sessionId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            HttpServletRequest request)
            throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> allCards =
                SessionProcessProjector.projectTimeline(timeline.listEntries(sessionId), json);
        return ResponseEntity.ok(SessionProcessProjector.paginate(allCards, page, size));
    }

    /** Paginated subagent records for the execution process drawer's Subagents tab. */
    @GetMapping("/{sessionId}/subagents")
    public ResponseEntity<Map<String, Object>> subagentsPage(
            @PathVariable String sessionId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            HttpServletRequest request)
            throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> allSubagents =
                SessionProcessProjector.projectSubagents(timeline.listEntries(sessionId), json);
        return ResponseEntity.ok(SessionProcessProjector.paginate(allSubagents, page, size));
    }

    /**
     * Records a chat message in the timeline. The frontend posts the visitor message before each
     * AG-UI run and the advisor message after a run completes, so the replay has both sides.
     *
     * <p>A visitor message still needs text. An advisor message may have no final text at all: a
     * round that only produced thinking, tool calls or usage is still recorded, so its process
     * survives a refresh. At least one of text / reasoning / toolCalls / usage must be present.
     */
    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> postMessage(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        synchronized (roundPersister == null ? this : roundPersister) {
            return postMessageWithinLock(sessionId, body, request);
        }
    }

    private ResponseEntity<Map<String, Object>> postMessageWithinLock(
            String sessionId, Map<String, Object> body, HttpServletRequest request)
            throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return body.containsKey("questionReply")
                    ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "question_not_found"))
                    : ResponseEntity.notFound().build();
        }
        String text = body.get("text") == null ? "" : body.get("text").toString();
        boolean advisor = "advisor".equals(body.get("role"));
        if (!"visitor".equals(body.get("role")) && body.containsKey("questionReply")) {
            return ResponseEntity.badRequest().body(Map.of("error", "question_reply_invalid"));
        }
        if (!advisor && text.isBlank() && !body.containsKey("questionReply")) {
            return ResponseEntity.badRequest().build();
        }
        if (!advisor) {
            try {
                boolean running =
                        aguiMvcController != null
                                && !"idle"
                                        .equals(
                                                aguiMvcController
                                                        .runtimeSnapshot(sessionId)
                                                        .get("phase"));
                var answer =
                        timeline.appendQuestionReply(
                                sessionId,
                                owner(request),
                                text,
                                body.get("attachments"),
                                body.get("questionReply"),
                                running);
                Map<String, Object> response = new LinkedHashMap<>(eventView(answer.event()));
                response.put("replayed", answer.replayed());
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } catch (QuestionService.QuestionException error) {
                return ResponseEntity.status(error.status()).body(Map.of("error", error.code()));
            }
        }
        if (advisor
                && text.isBlank()
                && !hasText(body.get("reasoning"))
                && isEmpty(body.get("toolCalls"))
                && isEmpty(body.get("usage"))) {
            return ResponseEntity.badRequest().build();
        }
        List<Map<String, Object>> parts;
        Map<String, Object> presentation;
        try {
            parts =
                    advisor
                            ? ConversationParts.validate(
                                    body.get("partsVersion"), body.get("parts"))
                            : null;
            presentation =
                    advisor
                            ? ConversationPresentation.validate(
                                    body.get("presentationVersion"),
                                    body.get("presentation"),
                                    parts)
                            : null;
            if (advisor && roundPersister != null && parts != null && !parts.isEmpty()) {
                String runId = String.valueOf(parts.get(0).get("runId"));
                if (roundPersister.belongsToAnotherRun(sessionId, runId)
                        && timeline.findAdvisorRun(sessionId, runId).isEmpty())
                    throw new IllegalArgumentException("Stale run identity");
                Map<String, Object> observed = roundPersister.presentation(sessionId, runId);
                if (observed != null)
                    presentation = ConversationPresentation.validate(1, observed, parts);
                else if (presentation != null
                        && timeline.findAdvisorRun(sessionId, runId).isEmpty())
                    throw new IllegalArgumentException(
                            "Presentation does not belong to an observed run");
            }
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
        TimelineStore.AppendedEvent appended;
        if (advisor) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            String reasoning = text(body, "reasoning");
            if (reasoning != null && !reasoning.isBlank()) payload.put("reasoning", reasoning);
            payload.put("mode", text(body, "mode") == null ? "" : text(body, "mode"));
            payload.put(
                    "domainName", text(body, "domainName") == null ? "" : text(body, "domainName"));
            if (parts != null) {
                payload.put("partsVersion", 1);
                payload.put("parts", parts);
            }
            if (presentation != null) {
                payload.put("presentationVersion", 1);
                payload.put("presentation", presentation);
            }
            String runId =
                    presentation != null
                            ? String.valueOf(presentation.get("runId"))
                            : parts != null && !parts.isEmpty()
                                    ? String.valueOf(parts.get(0).get("runId"))
                                    : null;
            try {
                appended =
                        timeline.saveAdvisorRound(
                                sessionId,
                                payload,
                                body.get("toolCalls"),
                                body.get("usage"),
                                body.get("subagentRuns"),
                                roundPersister == null ? 0 : roundPersister.turn(sessionId, runId));
            } catch (IllegalArgumentException conflict) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "run_content_conflict"));
            }
            if (roundPersister != null && runId != null)
                roundPersister.markFlushed(sessionId, runId);
            // First reply of an untitled session triggers async auto-naming (Claude/Codex
            // style); the response never waits for it.
            titleService.nameIfNeeded(session);
        } else {
            appended = timeline.appendVisitorMessage(sessionId, text, body.get("attachments"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(eventView(appended));
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    /**
     * One-click context compaction for the whole session. Runs synchronously against the
     * session's persisted agent state and reports the outcome; the frontend card shows a spinner
     * while the request is in flight. Conflicts (live run, compaction already underway) and
     * short-session skips come back with a reason code the UI localizes.
     */
    @PostMapping("/{sessionId}:compact")
    public ResponseEntity<Map<String, Object>> compact(
            @PathVariable String sessionId, HttpServletRequest request) throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (compactionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("outcome", "failed", "reason", "unavailable"));
        }
        SessionCompactionService.CompactionResult result = compactionService.compact(session);
        HttpStatus status =
                switch (result.outcome()) {
                    case SessionCompactionService.OUTCOME_COMPACTED,
                            SessionCompactionService.OUTCOME_SKIPPED ->
                            HttpStatus.OK;
                    case SessionCompactionService.OUTCOME_CONFLICT -> HttpStatus.CONFLICT;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", result.outcome());
        if (result.reason() != null) {
            view.put("reason", result.reason());
        }
        if (SessionCompactionService.OUTCOME_COMPACTED.equals(result.outcome())) {
            view.put("inputTokens", result.inputTokens());
            view.put("keptTokens", result.keptTokens());
        }
        return ResponseEntity.status(status).body(view);
    }

    /** Clears all context and timeline events of the session, keeping the session itself. */
    @PostMapping("/{sessionId}:clear")
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable String sessionId, HttpServletRequest request) throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (SessionStore.STATUS_RUNNING.equals(session.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("outcome", "conflict", "reason", "session_running"));
        }
        if (compactionService == null)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("outcome", "failed", "reason", "state_unavailable"));
        try {
            compactionService.clearSessionContext(session);
        } catch (IllegalStateException error) {
            boolean busy = "session_busy".equals(error.getMessage());
            return ResponseEntity.status(
                            busy ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            Map.of(
                                    "outcome",
                                    "failed",
                                    "reason",
                                    busy ? "session_running" : "state_unavailable"));
        }
        return ResponseEntity.ok(Map.of("outcome", "cleared"));
    }

    /**
     * Interrupts any active execution on the session, immediately flushing
     * accumulated progress to the timeline and releasing active run locks.
     */
    @PostMapping("/{sessionId}:interrupt")
    public ResponseEntity<Map<String, Object>> interrupt(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        SessionStore.SessionRow session = owned(sessionId, request);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        String expectedRunId = body == null ? null : text(body, "expectedRunId");
        if (expectedRunId != null
                && (roundPersister != null
                                && roundPersister.belongsToAnotherRun(sessionId, expectedRunId)
                        || aguiMvcController != null
                                && aguiMvcController.activeRunId(sessionId) != null
                                && !expectedRunId.equals(
                                        aguiMvcController.activeRunId(sessionId)))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("outcome", "conflict", "reason", "stale_run"));
        }
        if (aguiMvcController != null) {
            aguiMvcController.interrupt(sessionId, expectedRunId);
        }
        if (roundPersister != null) {
            roundPersister.flush(sessionId, expectedRunId);
        }
        if (toolRuntimeCoordinator != null) {
            if (expectedRunId == null) toolRuntimeCoordinator.releaseLease(sessionId);
            else toolRuntimeCoordinator.releaseLease(sessionId, expectedRunId);
        }
        if (aguiMvcController == null || !aguiMvcController.hasActiveRun(sessionId))
            sessions.updateStatus(sessionId, SessionStore.STATUS_INTERRUPTED);
        return ResponseEntity.ok(Map.of("outcome", "interrupted", "sessionId", sessionId));
    }

    public ResponseEntity<Map<String, Object>> interrupt(
            String sessionId, HttpServletRequest request) throws SQLException {
        return interrupt(sessionId, null, request);
    }

    private static boolean isEmpty(Object value) {
        return !(value instanceof List<?> list) || list.isEmpty();
    }

    private SessionStore.SessionRow owned(String sessionId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return sessions.find(sessionId)
                .filter(session -> session.ownerUserId().equals(account.userId()))
                .orElse(null);
    }

    private static String owner(HttpServletRequest request) {
        return ApiAuthenticationFilter.account(request).orElseThrow().userId();
    }

    private Map<String, Object> sessionView(SessionStore.SessionRow session) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", session.sessionId());
        view.put("title", session.title());
        view.put("status", session.status());
        view.put("agentId", session.agentId());
        view.put("createdAt", session.createdAt());
        view.put("updatedAt", session.updatedAt());
        return view;
    }

    private Map<String, Object> eventView(TimelineStore.AppendedEvent appended) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", appended.seq());
        view.put("turn", appended.turn());
        view.put("kind", appended.kind());
        view.put("payload", appended.payload());
        return view;
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            return raw;
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }
}
