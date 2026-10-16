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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST endpoints for managing agent creation drafts. */
@RestController
@RequestMapping("/api/advisor/agent-creation-drafts")
public class AgentCreationController {

    private final AgentCreationService creationService;
    private final AgentPreviewService previewService;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentCreationController(
            AgentCreationService creationService,
            AgentPreviewService previewService,
            ObjectMapper json) {
        this.creationService = creationService;
        this.previewService = previewService;
        this.json = json;
    }

    public AgentCreationController(AgentCreationService creationService, ObjectMapper json) {
        this(creationService, null, json);
    }

    public record DraftDto(
            String draftId,
            String ownerUserId,
            String mode,
            String sourceAgentId,
            String purpose,
            String name,
            String description,
            String behavior,
            List<String> tools,
            List<String> skills,
            List<Map<String, Object>> resources,
            int revision,
            long createdAt,
            long updatedAt,
            String activationState,
            String activatedAgentId,
            String activationOperationId) {}

    public record CreateDraftRequest(
            String mode,
            String sourceAgentId,
            String purpose,
            String name,
            String description,
            String behavior,
            List<String> tools,
            List<String> skills,
            List<Map<String, Object>> resources) {}

    public record UpdateDraftRequest(
            String purpose,
            String name,
            String description,
            String behavior,
            List<String> tools,
            List<String> skills,
            List<Map<String, Object>> resources) {}

    public record SubmitGenerationRequest(Integer baseRevision) {}

    public record ApplyCandidateRequest(String name, String description, String behavior) {}

    public record CheckDraftRequest(Integer revision) {}

    public record SubmitActivationRequest(Integer revision) {}

    public record CreatePreviewRequest(Integer revision) {}

    public record RunPreviewRequest(String input) {}

    public record PreviewDto(
            String previewId,
            String draftId,
            int draftRevision,
            String ownerUserId,
            String state,
            Object history,
            long createdAt,
            long updatedAt,
            long expiresAt) {}

    public record OperationDto(
            String operationId,
            String draftId,
            String operationType,
            String requestKey,
            int baseRevision,
            String state,
            String stage,
            Object candidate,
            String preallocatedAgentId,
            String errorCode,
            String errorMessage,
            long startedAt,
            Long finishedAt) {}

    @PostMapping
    public ResponseEntity<DraftDto> create(
            @RequestBody(required = false) CreateDraftRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        CreateDraftRequest req =
                request != null
                        ? request
                        : new CreateDraftRequest(
                                null, null, null, null, null, null, null, null, null);
        var command =
                new AgentCreationService.CreateDraftCommand(
                        req.mode(),
                        req.sourceAgentId(),
                        req.purpose(),
                        req.name(),
                        req.description(),
                        req.behavior(),
                        req.tools(),
                        req.skills(),
                        req.resources());
        AgentCreationDraftStore.DraftRow draft =
                creationService.createDraft(account, command, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(draft));
    }

    @GetMapping
    public List<DraftDto> list(HttpServletRequest httpRequest) throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        List<AgentCreationDraftStore.DraftRow> drafts =
                creationService.listDrafts(account, httpRequest.getParameter("q"));
        List<DraftDto> result = new ArrayList<>();
        for (var draft : drafts) {
            result.add(toDto(draft));
        }
        return result;
    }

    @GetMapping("/{draftId}")
    public DraftDto detail(@PathVariable String draftId, HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        AgentCreationDraftStore.DraftRow draft = creationService.getDraft(account, draftId);
        return toDto(draft);
    }

    @PatchMapping("/{draftId}")
    public DraftDto patch(
            @PathVariable String draftId,
            @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
            @RequestBody UpdateDraftRequest request,
            HttpServletRequest httpRequest)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "If-Match header with expected revision is required");
        }
        int expectedRevision;
        try {
            expectedRevision = Integer.parseInt(ifMatchHeader.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid If-Match revision number");
        }

        var command =
                new AgentCreationService.UpdateDraftCommand(
                        request.purpose(),
                        request.name(),
                        request.description(),
                        request.behavior(),
                        request.tools(),
                        request.skills(),
                        request.resources());

        AgentCreationDraftStore.DraftRow updated =
                creationService.updateDraft(account, draftId, expectedRevision, command);
        return toDto(updated);
    }

    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> delete(@PathVariable String draftId, HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        creationService.deleteDraft(account, draftId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{draftId}/generations")
    public ResponseEntity<OperationDto> submitGeneration(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) SubmitGenerationRequest request,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        int baseRevision =
                request != null && request.baseRevision() != null ? request.baseRevision() : 1;
        var op = creationService.submitGeneration(account, draftId, baseRevision, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toOperationDto(op));
    }

    @GetMapping("/{draftId}/generations/{operationId}")
    public ResponseEntity<OperationDto> getGeneration(
            @PathVariable String draftId,
            @PathVariable String operationId,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        var op = creationService.getGeneration(account, draftId, operationId);
        return ResponseEntity.ok(toOperationDto(op));
    }

    @PostMapping("/{draftId}/generations/{operationId}/cancel")
    public ResponseEntity<OperationDto> cancelGeneration(
            @PathVariable String draftId,
            @PathVariable String operationId,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        var op = creationService.cancelGeneration(account, draftId, operationId);
        return ResponseEntity.ok(toOperationDto(op));
    }

    @PostMapping("/{draftId}/generations/{operationId}/apply")
    public ResponseEntity<DraftDto> applyCandidate(
            @PathVariable String draftId,
            @PathVariable String operationId,
            @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
            @RequestBody(required = false) ApplyCandidateRequest request,
            HttpServletRequest httpRequest)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "If-Match header is required");
        }
        int expectedRevision;
        try {
            expectedRevision = Integer.parseInt(ifMatchHeader.trim().replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid If-Match revision number");
        }
        var command =
                request != null
                        ? new AgentCreationService.ApplyCandidateCommand(
                                request.name(), request.description(), request.behavior())
                        : null;
        var updated =
                creationService.applyCandidate(
                        account, draftId, operationId, expectedRevision, command);
        return ResponseEntity.ok(toDto(updated));
    }

    private OperationDto toOperationDto(AgentCreationOperationStore.CreationOperation op) {
        Object candidateObj = null;
        if (op.candidateJson() != null && !op.candidateJson().isBlank()) {
            try {
                candidateObj = json.readValue(op.candidateJson(), Object.class);
            } catch (IOException ignored) {
                candidateObj = op.candidateJson();
            }
        }
        return new OperationDto(
                op.operationId(),
                op.draftId(),
                op.operationType(),
                op.requestKey(),
                op.baseRevision(),
                op.state(),
                op.stage(),
                candidateObj,
                op.preallocatedAgentId(),
                op.errorCode(),
                op.errorMessage(),
                op.startedAt(),
                op.finishedAt());
    }

    @PostMapping("/{draftId}/checks")
    public ResponseEntity<AgentCreationService.CheckResult> checkDraft(
            @PathVariable String draftId,
            @RequestBody(required = false) CheckDraftRequest request,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        Integer revision = request != null ? request.revision() : null;
        var result = creationService.checkDraft(account, draftId, revision);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{draftId}/activations")
    public ResponseEntity<?> submitActivation(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) SubmitActivationRequest request,
            HttpServletRequest httpRequest)
            throws Exception {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        int revision = request != null && request.revision() != null ? request.revision() : 1;
        var checkResult = creationService.checkDraft(account, draftId, revision);
        boolean hasErrors =
                checkResult.issues().stream().anyMatch(i -> "error".equalsIgnoreCase(i.severity()));
        if (hasErrors) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(checkResult);
        }
        var op = creationService.submitActivation(account, draftId, revision, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toOperationDto(op));
    }

    @GetMapping("/{draftId}/activations/{operationId}")
    public ResponseEntity<OperationDto> getActivation(
            @PathVariable String draftId,
            @PathVariable String operationId,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        var op = creationService.getGeneration(account, draftId, operationId);
        return ResponseEntity.ok(toOperationDto(op));
    }

    @PostMapping("/{draftId}/previews")
    public ResponseEntity<PreviewDto> createPreview(
            @PathVariable String draftId,
            @RequestBody(required = false) CreatePreviewRequest request,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (previewService == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Preview service unavailable");
        }
        int revision = request != null && request.revision() != null ? request.revision() : 1;
        var preview = previewService.createPreview(account, draftId, revision);
        return ResponseEntity.status(HttpStatus.CREATED).body(toPreviewDto(preview));
    }

    @GetMapping("/{draftId}/previews/{previewId}")
    public ResponseEntity<PreviewDto> getPreview(
            @PathVariable String draftId,
            @PathVariable String previewId,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (previewService == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Preview service unavailable");
        }
        var preview = previewService.getPreview(account, draftId, previewId);
        return ResponseEntity.ok(toPreviewDto(preview));
    }

    @PostMapping("/{draftId}/previews/{previewId}/runs")
    public ResponseEntity<Map<String, Object>> runPreview(
            @PathVariable String draftId,
            @PathVariable String previewId,
            @RequestBody(required = false) RunPreviewRequest request,
            HttpServletRequest httpRequest)
            throws Exception {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (previewService == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Preview service unavailable");
        }
        String input = request != null ? request.input() : "";
        String reply = previewService.runPreview(account, draftId, previewId, input);
        return ResponseEntity.ok(Map.of("previewId", previewId, "reply", reply));
    }

    @DeleteMapping("/{draftId}/previews/{previewId}")
    public ResponseEntity<Void> deletePreview(
            @PathVariable String draftId,
            @PathVariable String previewId,
            HttpServletRequest httpRequest)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(httpRequest).orElseThrow();
        if (previewService == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Preview service unavailable");
        }
        previewService.deletePreview(account, draftId, previewId);
        return ResponseEntity.noContent().build();
    }

    private PreviewDto toPreviewDto(AgentPreviewStore.PreviewRow preview) {
        Object historyObj = List.of();
        if (preview.historyJson() != null && !preview.historyJson().isBlank()) {
            try {
                historyObj = json.readValue(preview.historyJson(), Object.class);
            } catch (IOException ignored) {
            }
        }
        return new PreviewDto(
                preview.previewId(),
                preview.draftId(),
                preview.draftRevision(),
                preview.ownerUserId(),
                preview.state(),
                historyObj,
                preview.createdAt(),
                preview.updatedAt(),
                preview.expiresAt());
    }

    private DraftDto toDto(AgentCreationDraftStore.DraftRow draft) throws SQLException {
        List<String> tools = parseStringList(draft.toolsJson());
        List<String> skills = parseStringList(draft.skillsJson());
        List<Map<String, Object>> resources = parseResourceList(draft.resourcesJson());
        return new DraftDto(
                draft.draftId(),
                draft.ownerUserId(),
                draft.mode(),
                draft.sourceAgentId(),
                draft.purpose(),
                draft.name(),
                draft.description(),
                draft.behavior(),
                tools,
                skills,
                resources,
                draft.revision(),
                draft.createdAt(),
                draft.updatedAt(),
                creationService.activation(draft.draftId()).state(),
                creationService.activation(draft.draftId()).agentId(),
                creationService.activation(draft.draftId()).operationId());
    }

    private List<String> parseStringList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return json.readValue(jsonStr, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parseResourceList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return json.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }
}
