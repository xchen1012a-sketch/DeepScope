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
package io.deepscope.advisor.command;

import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.session.SessionCompactionService;
import io.deepscope.advisor.session.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for discovering and executing controlled slash commands.
 */
@RestController
@RequestMapping("/api/advisor/commands")
public class CommandController {

    public record CommandDto(
            String commandId,
            String code,
            String name,
            String description,
            String origin,
            String targetType,
            String targetId,
            String paramsJson,
            int sortOrder) {

        public static CommandDto from(CommandCatalog.CommandRow row) {
            return new CommandDto(
                    row.commandId(),
                    row.code(),
                    row.name(),
                    row.description(),
                    row.origin(),
                    row.targetType(),
                    row.targetId(),
                    row.paramsJson(),
                    row.sortOrder());
        }
    }

    public record ExecuteRequest(String sessionId, String args) {}

    public record ExecuteResponse(
            String outcome,
            String targetType,
            String targetId,
            String reason,
            Map<String, Object> data) {

        public static ExecuteResponse of(String outcome, String targetType, String targetId) {
            return new ExecuteResponse(outcome, targetType, targetId, null, Map.of());
        }
    }

    private final CommandCatalog commands;
    private final SessionStore sessions;
    private final SessionCompactionService compactionService;
    private final io.deepscope.advisor.session.TimelineStore timelineStore;

    public CommandController(
            CommandCatalog commands,
            SessionStore sessions,
            SessionCompactionService compactionService,
            io.deepscope.advisor.session.TimelineStore timelineStore) {
        this.commands = commands;
        this.sessions = sessions;
        this.compactionService = compactionService;
        this.timelineStore = timelineStore;
    }

    @GetMapping
    public List<CommandDto> listAvailable(HttpServletRequest request) throws SQLException {
        String userId =
                ApiAuthenticationFilter.account(request)
                        .map(AccountStore.Account::userId)
                        .orElse(null);
        return commands.listAvailable(userId).stream().map(CommandDto::from).toList();
    }

    @PostMapping("/{code}:execute")
    public ResponseEntity<ExecuteResponse> execute(
            @PathVariable String code,
            @RequestBody(required = false) ExecuteRequest body,
            HttpServletRequest request)
            throws SQLException {
        String currentUserId =
                ApiAuthenticationFilter.account(request)
                        .map(AccountStore.Account::userId)
                        .orElse(null);
        Optional<CommandCatalog.CommandRow> found =
                commands.findAvailableByCode(code, currentUserId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        CommandCatalog.CommandRow cmd = found.get();
        // Security check: custom command belongs to owner
        if (CommandCatalog.ORIGIN_CUSTOM.equals(cmd.origin())
                && !cmd.ownerUserId().equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (CommandCatalog.TARGET_ACTION.equals(cmd.targetType())) {
            String sessionId = body == null ? null : body.sessionId();
            if ("compact".equals(cmd.targetId())
                    || "compact".equals(cmd.code())
                    || "compress".equals(cmd.code())) {
                if (sessionId == null || sessionId.isBlank()) {
                    return ResponseEntity.badRequest().build();
                }
                Optional<SessionStore.SessionRow> sessionOpt = sessions.find(sessionId);
                if (sessionOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                SessionStore.SessionRow session = sessionOpt.get();
                if (currentUserId != null && !session.ownerUserId().equals(currentUserId)) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                SessionCompactionService.CompactionResult result =
                        compactionService.compact(session);
                HttpStatus status =
                        switch (result.outcome()) {
                            case SessionCompactionService.OUTCOME_CONFLICT -> HttpStatus.CONFLICT;
                            case SessionCompactionService.OUTCOME_FAILED ->
                                    HttpStatus.INTERNAL_SERVER_ERROR;
                            default -> HttpStatus.OK;
                        };
                Map<String, Object> data =
                        result.outcome().equals(SessionCompactionService.OUTCOME_COMPACTED)
                                ? Map.of(
                                        "inputTokens",
                                        result.inputTokens(),
                                        "keptTokens",
                                        result.keptTokens())
                                : Map.of();
                return ResponseEntity.status(status)
                        .body(
                                new ExecuteResponse(
                                        result.outcome(),
                                        cmd.targetType(),
                                        cmd.targetId(),
                                        result.reason(),
                                        data));
            }
            if ("clear".equals(cmd.targetId()) || "clear".equals(cmd.code())) {
                if (sessionId == null || sessionId.isBlank()) {
                    return ResponseEntity.badRequest().build();
                }
                Optional<SessionStore.SessionRow> sessionOpt = sessions.find(sessionId);
                if (sessionOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                SessionStore.SessionRow session = sessionOpt.get();
                if (currentUserId != null && !session.ownerUserId().equals(currentUserId)) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                if (SessionStore.STATUS_RUNNING.equals(session.status())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new ExecuteResponse(
                                            "conflict",
                                            cmd.targetType(),
                                            cmd.targetId(),
                                            "session_running",
                                            Map.of()));
                }
                try {
                    compactionService.clearSessionContext(session);
                } catch (IllegalStateException error) {
                    boolean busy = "session_busy".equals(error.getMessage());
                    return ResponseEntity.status(
                                    busy ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    new ExecuteResponse(
                                            "failed",
                                            cmd.targetType(),
                                            cmd.targetId(),
                                            busy ? "session_running" : "state_unavailable",
                                            Map.of()));
                }
                return ResponseEntity.ok(
                        ExecuteResponse.of("cleared", cmd.targetType(), cmd.targetId()));
            }
            return ResponseEntity.ok(
                    ExecuteResponse.of("executed", cmd.targetType(), cmd.targetId()));
        }

        // For skill, mcp, prompt: resolved and ready for client attachment
        return ResponseEntity.ok(ExecuteResponse.of("resolved", cmd.targetType(), cmd.targetId()));
    }
}
