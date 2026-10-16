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
package io.agentscope.advisor.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.access.AccessPoints;
import io.agentscope.advisor.auth.AccountStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Service coordinating draft creation, source agent copying, validation, and lifecycle. */
public class AgentCreationService {

    private static final Logger log = LoggerFactory.getLogger(AgentCreationService.class);

    private final AgentCreationDraftStore drafts;
    private final AgentCreationOperationStore operations;
    private final AgentStore agents;
    private final AgentWorkspace workspace;
    private final BehaviorDraftService behaviorService;
    private final AgentProvisioner provisioner;
    private final AccessGuard access;
    private final ObjectMapper json;

    private final ThreadPoolExecutor generationExecutor;
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public AgentCreationService(
            AgentCreationDraftStore drafts,
            AgentCreationOperationStore operations,
            AgentStore agents,
            AgentWorkspace workspace,
            BehaviorDraftService behaviorService,
            AccessGuard access,
            AgentProvisioner provisioner,
            ObjectMapper json) {
        this.drafts = drafts;
        this.operations = operations;
        this.agents = agents;
        this.workspace = workspace;
        this.behaviorService = behaviorService;
        this.access = access;
        this.provisioner = provisioner;
        this.json = json;
        this.generationExecutor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(32),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public AgentCreationService(
            AgentCreationDraftStore drafts,
            AgentCreationOperationStore operations,
            AgentStore agents,
            AgentWorkspace workspace,
            BehaviorDraftService behaviorService,
            AccessGuard access,
            ObjectMapper json) {
        this(drafts, operations, agents, workspace, behaviorService, access, null, json);
    }

    public AgentCreationService(
            AgentCreationDraftStore drafts,
            AgentStore agents,
            AgentWorkspace workspace,
            AccessGuard access,
            ObjectMapper json) {
        this(drafts, null, agents, workspace, null, access, json);
    }

    public record CreateDraftCommand(
            String mode,
            String sourceAgentId,
            String purpose,
            String name,
            String description,
            String behavior,
            List<String> tools,
            List<String> skills,
            List<Map<String, Object>> resources) {}

    public record UpdateDraftCommand(
            String purpose,
            String name,
            String description,
            String behavior,
            List<String> tools,
            List<String> skills,
            List<Map<String, Object>> resources) {}

    public record ApplyCandidateCommand(String name, String description, String behavior) {}

    public AgentCreationDraftStore.DraftRow createDraft(
            AccountStore.Account account, CreateDraftCommand command, String idempotencyKey)
            throws SQLException, IOException {
        String mode =
                command.mode() == null || command.mode().isBlank()
                        ? "describe"
                        : command.mode().strip();
        String name = command.name();
        String description = command.description();
        String behavior = command.behavior();
        String toolsJson =
                command.tools() == null ? "[]" : json.writeValueAsString(command.tools());
        String skillsJson =
                command.skills() == null ? "[]" : json.writeValueAsString(command.skills());
        String resourcesJson =
                command.resources() == null ? "[]" : json.writeValueAsString(command.resources());

        if ("copy".equalsIgnoreCase(mode)) {
            String sourceId = command.sourceAgentId();
            if (sourceId == null || sourceId.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "sourceAgentId is required for copy mode");
            }
            AgentStore.AgentRow source =
                    agents.find(sourceId)
                            .filter(
                                    row ->
                                            AgentStore.canView(
                                                    row, account.userId(), seesAll(account)))
                            .orElse(null);
            if (source == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "source_unavailable");
            }

            if (name == null || name.isBlank()) {
                name = source.name() + " 副本";
            }
            if (description == null || description.isBlank()) {
                description = source.description();
            }
            if (behavior == null || behavior.isBlank()) {
                try {
                    behavior = workspace.read(source, "AGENTS.md");
                } catch (IOException | ResponseStatusException ignored) {
                    behavior = "";
                }
            }
        }

        String requestHash = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            requestHash =
                    hashRequest(
                            mode,
                            command.sourceAgentId(),
                            command.purpose(),
                            name,
                            description,
                            behavior);
        }

        return drafts.create(
                account.userId(),
                mode,
                command.sourceAgentId(),
                command.purpose(),
                name,
                description,
                behavior,
                toolsJson,
                skillsJson,
                resourcesJson,
                idempotencyKey,
                requestHash);
    }

    public AgentCreationDraftStore.DraftRow getDraft(AccountStore.Account account, String draftId)
            throws SQLException {
        AgentCreationDraftStore.DraftRow draft =
                drafts.findById(draftId)
                        .filter(
                                row ->
                                        AgentCreationDraftStore.canView(
                                                row, account.userId(), seesAll(account)))
                        .orElse(null);
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "draft_unavailable");
        }
        return draft;
    }

    public List<AgentCreationDraftStore.DraftRow> listDrafts(AccountStore.Account account)
            throws SQLException {
        return listDrafts(account, null);
    }

    public List<AgentCreationDraftStore.DraftRow> listDrafts(
            AccountStore.Account account, String query) throws SQLException {
        return drafts.listVisible(account.userId(), seesAll(account), query);
    }

    public AgentCreationDraftStore.DraftRow updateDraft(
            AccountStore.Account account,
            String draftId,
            int expectedRevision,
            UpdateDraftCommand command)
            throws SQLException, JsonProcessingException {
        AgentCreationDraftStore.DraftRow existing = getDraft(account, draftId);
        drafts.requireEditable(draftId);
        if (!AgentCreationDraftStore.canEdit(existing, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }

        String purpose = command.purpose() != null ? command.purpose() : existing.purpose();
        String name = command.name() != null ? command.name() : existing.name();
        String desc =
                command.description() != null ? command.description() : existing.description();
        String behavior = command.behavior() != null ? command.behavior() : existing.behavior();
        String toolsJson =
                command.tools() != null
                        ? json.writeValueAsString(command.tools())
                        : existing.toolsJson();
        String skillsJson =
                command.skills() != null
                        ? json.writeValueAsString(command.skills())
                        : existing.skillsJson();
        String resourcesJson =
                command.resources() != null
                        ? json.writeValueAsString(command.resources())
                        : existing.resourcesJson();

        return drafts.update(
                draftId,
                expectedRevision,
                new AgentCreationDraftStore.DraftUpdate(
                        purpose, name, desc, behavior, toolsJson, skillsJson, resourcesJson));
    }

    public void deleteDraft(AccountStore.Account account, String draftId) throws SQLException {
        AgentCreationDraftStore.DraftRow existing = getDraft(account, draftId);
        if (!AgentCreationDraftStore.canEdit(existing, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }
        drafts.delete(draftId);
    }

    public AgentCreationOperationStore.CreationOperation submitGeneration(
            AccountStore.Account account, String draftId, int baseRevision, String idempotencyKey)
            throws SQLException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        drafts.requireEditable(draftId);
        if (!AgentCreationDraftStore.canEdit(draft, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }
        if (draft.revision() != baseRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision_conflict");
        }
        if (operations == null || behaviorService == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Candidate generation is unconfigured");
        }

        String reqKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey.strip()
                        : UUID.randomUUID().toString();
        String reqHash =
                hashRequest(
                        "generation",
                        draftId,
                        String.valueOf(baseRevision),
                        draft.purpose(),
                        draft.name());

        var existing =
                operations.findByDraftAndKey(
                        draftId, AgentCreationOperationStore.TYPE_GENERATION, reqKey);
        if (existing.isPresent()) {
            var op = existing.get();
            if (Objects.equals(op.requestHash(), reqHash)) {
                return op;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency_conflict");
        }

        if (generationExecutor.getQueue().remainingCapacity() == 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "queue_full");
        }

        AgentCreationOperationStore.CreationOperation op =
                operations.create(
                        draftId,
                        AgentCreationOperationStore.TYPE_GENERATION,
                        reqKey,
                        reqHash,
                        baseRevision);

        try {
            Future<?> task =
                    generationExecutor.submit(
                            () ->
                                    runGenerationWorker(
                                            op.operationId(),
                                            draftId,
                                            draft.purpose(),
                                            draft.name(),
                                            draft.description()));
            runningTasks.put(op.operationId(), task);
        } catch (RejectedExecutionException rejected) {
            try {
                operations.updateState(
                        op.operationId(),
                        AgentCreationOperationStore.STATE_FAILED,
                        "queue_full",
                        null,
                        null,
                        "queue_full",
                        "Generation queue is full");
            } catch (SQLException ignored) {
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "queue_full");
        }

        return op;
    }

    private void runGenerationWorker(
            String operationId,
            String draftId,
            String purpose,
            String currentName,
            String currentDesc) {
        try {
            var before = operations.findById(operationId).orElse(null);
            if (before == null || !List.of("queued", "running").contains(before.state())) return;
            operations.updateState(
                    operationId,
                    AgentCreationOperationStore.STATE_RUNNING,
                    "calling_model",
                    null,
                    null,
                    null,
                    null);

            BehaviorDraftService.Candidate candidate =
                    behaviorService.generateCandidate(purpose, currentName, currentDesc);
            String candidateJson = json.writeValueAsString(candidate);

            var currentOp = operations.findById(operationId).orElse(null);
            if (currentOp != null
                    && AgentCreationOperationStore.STATE_CANCELED.equals(currentOp.state())) {
                return;
            }

            operations.updateState(
                    operationId,
                    AgentCreationOperationStore.STATE_SUCCEEDED,
                    "completed",
                    candidateJson,
                    null,
                    null,
                    null);
        } catch (Exception e) {
            try {
                var currentOp = operations.findById(operationId).orElse(null);
                if (currentOp != null
                        && AgentCreationOperationStore.STATE_CANCELED.equals(currentOp.state())) {
                    return;
                }
                String errorCode = "model_unavailable";
                if (e instanceof IllegalStateException && "empty_response".equals(e.getMessage())) {
                    errorCode = "empty_response";
                } else if (e instanceof java.util.concurrent.TimeoutException) {
                    errorCode = "generation_timeout";
                }
                operations.updateState(
                        operationId,
                        AgentCreationOperationStore.STATE_FAILED,
                        "error",
                        null,
                        null,
                        errorCode,
                        e.getMessage());
            } catch (SQLException sqlEx) {
                log.warn("Failed to persist operation error: {}", sqlEx.getMessage());
            }
        } finally {
            runningTasks.remove(operationId);
        }
    }

    public AgentCreationOperationStore.CreationOperation getGeneration(
            AccountStore.Account account, String draftId, String operationId) throws SQLException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        if (operations == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Operation store unconfigured");
        }
        return operations
                .findById(operationId)
                .filter(op -> op.draftId().equals(draftId))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "generation_unavailable"));
    }

    public AgentCreationOperationStore.CreationOperation cancelGeneration(
            AccountStore.Account account, String draftId, String operationId) throws SQLException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        if (!AgentCreationDraftStore.canEdit(draft, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }
        if (operations == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Operation store unconfigured");
        }
        var op =
                operations
                        .findById(operationId)
                        .filter(o -> o.draftId().equals(draftId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "generation_unavailable"));

        if (AgentCreationOperationStore.STATE_SUCCEEDED.equals(op.state())
                || AgentCreationOperationStore.STATE_FAILED.equals(op.state())
                || AgentCreationOperationStore.STATE_INTERRUPTED.equals(op.state())
                || AgentCreationOperationStore.STATE_CANCELED.equals(op.state())) {
            return op;
        }

        operations.updateState(
                operationId,
                AgentCreationOperationStore.STATE_CANCELED,
                "canceled",
                null,
                null,
                null,
                null);
        Future<?> task = runningTasks.remove(operationId);
        if (task != null) task.cancel(true);
        return operations.findById(operationId).orElseThrow();
    }

    public AgentCreationDraftStore.DraftRow applyCandidate(
            AccountStore.Account account,
            String draftId,
            String operationId,
            int expectedRevision,
            ApplyCandidateCommand command)
            throws SQLException, IOException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        if (!AgentCreationDraftStore.canEdit(draft, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }
        if (operations == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Operation store unconfigured");
        }
        var op =
                operations
                        .findById(operationId)
                        .filter(o -> o.draftId().equals(draftId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "generation_unavailable"));

        if (!AgentCreationOperationStore.STATE_SUCCEEDED.equals(op.state())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Candidate generation is not succeeded yet");
        }

        if (op.baseRevision() != expectedRevision || draft.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "generation_stale");
        }

        BehaviorDraftService.Candidate candidate = null;
        if (op.candidateJson() != null && !op.candidateJson().isBlank()) {
            candidate = json.readValue(op.candidateJson(), BehaviorDraftService.Candidate.class);
        }

        String name = draft.name();
        String desc = draft.description();
        String behavior = draft.behavior();

        if (candidate != null) {
            if (candidate.name() != null && !candidate.name().isBlank()) {
                name = candidate.name();
            }
            if (candidate.description() != null && !candidate.description().isBlank()) {
                desc = candidate.description();
            }
            if (candidate.behavior() != null && !candidate.behavior().isBlank()) {
                behavior = candidate.behavior();
            }
        }

        if (command != null) {
            if (command.name() != null && !command.name().isBlank()) {
                name = command.name();
            }
            if (command.description() != null && !command.description().isBlank()) {
                desc = command.description();
            }
            if (command.behavior() != null && !command.behavior().isBlank()) {
                behavior = command.behavior();
            }
        }

        return drafts.update(
                draftId,
                expectedRevision,
                new AgentCreationDraftStore.DraftUpdate(
                        draft.purpose(),
                        name,
                        desc,
                        behavior,
                        draft.toolsJson(),
                        draft.skillsJson(),
                        draft.resourcesJson()));
    }

    public record CheckIssue(
            String code, String severity, String section, String messageKey, String resourceId) {}

    public record CheckResult(List<CheckIssue> issues) {}

    public CheckResult checkDraft(
            AccountStore.Account account, String draftId, Integer expectedRevision)
            throws SQLException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        if (expectedRevision != null && draft.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision_conflict");
        }
        List<CheckIssue> issues = new ArrayList<>();
        if (draft.name() == null || draft.name().isBlank()) {
            issues.add(
                    new CheckIssue(
                            "missing_name", "error", "name", "validation.missing_name", null));
        }
        if (draft.behavior() == null || draft.behavior().isBlank()) {
            issues.add(
                    new CheckIssue(
                            "missing_behavior",
                            "error",
                            "behavior",
                            "validation.missing_behavior",
                            null));
        }
        return new CheckResult(issues);
    }

    public AgentCreationOperationStore.CreationOperation submitActivation(
            AccountStore.Account account,
            String draftId,
            int expectedRevision,
            String idempotencyKey)
            throws SQLException, IOException {
        AgentCreationDraftStore.DraftRow draft = getDraft(account, draftId);
        if (!AgentCreationDraftStore.canEdit(draft, account.userId(), seesAll(account))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "edit_forbidden");
        }
        if (draft.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision_conflict");
        }

        CheckResult checkResult = checkDraft(account, draftId, expectedRevision);
        boolean hasErrors =
                checkResult.issues().stream().anyMatch(i -> "error".equalsIgnoreCase(i.severity()));
        if (hasErrors) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "activation_check_failed");
        }

        String reqKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey.strip()
                        : UUID.randomUUID().toString();
        String reqHash =
                hashRequest(
                        "activation",
                        draftId,
                        String.valueOf(expectedRevision),
                        draft.name(),
                        draft.behavior());

        var activation = new AgentActivationStore(drafts.dataSource(), json);
        var reservation = activation.reserve(draftId, expectedRevision, reqKey, reqHash);
        if (!reservation.acquired())
            return operations.findById(reservation.operationId()).orElseThrow();
        String preallocatedAgentId = reservation.agentId();
        try {
            AgentStore.AgentRow agentRow = activation.stage(reservation, draft, agents);
            workspace.write(
                    agentRow,
                    "AGENTS.md",
                    draft.behavior(),
                    !java.nio.file.Files.exists(
                            java.nio.file.Path.of(agentRow.workspaceDir()).resolve("AGENTS.md")));
            if (provisioner != null) {
                var prepared = provisioner.prepare(agentRow);
                try {
                    var catalog =
                            new io.agentscope.advisor.tool.ToolCatalog(drafts.dataSource(), json);
                    for (var tool : catalog.effectiveTools(agentRow.agentId())) {
                        if (prepared.harness().getToolkit().getTool(tool.name()) == null)
                            throw new java.io.IOException("Selected tool could not be prepared");
                    }
                    provisioner
                            .commit(agentRow.agentId(), prepared)
                            .forEach(io.agentscope.core.tool.mcp.McpClientWrapper::close);
                } catch (Exception error) {
                    prepared.harness().close();
                    prepared.connections()
                            .forEach(io.agentscope.core.tool.mcp.McpClientWrapper::close);
                    throw error;
                }
            }
            activation.finish(draftId, reservation, true);
        } catch (Exception e) {
            log.warn("Activation failed, compensating: {}", e.getMessage());
            try {
                if (provisioner != null) {
                    provisioner.unregister(preallocatedAgentId);
                }
            } catch (Exception ignored) {
            }
            try {
                activation.finish(draftId, reservation, false);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "activation_recovery_required", e);
            }
            if (e instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "activation_failed", e);
        }
        return operations.findById(reservation.operationId()).orElseThrow();
    }

    public AgentCreationDraftStore.Activation activation(String draftId) throws SQLException {
        return drafts.activation(draftId);
    }

    boolean seesAll(AccountStore.Account account) {
        try {
            return access.seesAll(account, AccessPoints.AGENT_MANAGE);
        } catch (SQLException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "permission_storage_unavailable", e);
        }
    }

    private String hashRequest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part != null) {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
