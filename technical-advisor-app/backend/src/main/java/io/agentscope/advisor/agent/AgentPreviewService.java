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
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.context.ContextModelCalls;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service managing preview trial sandbox runs without touching official SessionStore or TimelineStore.
 */
public class AgentPreviewService {

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);

    private final AgentPreviewStore previewStore;
    private final AgentCreationDraftStore drafts;
    private final Model model;
    private final ObjectMapper json;
    private final io.agentscope.advisor.access.AccessGuard access;

    public AgentPreviewService(
            AgentPreviewStore previewStore,
            AgentCreationDraftStore drafts,
            Model model,
            ObjectMapper json) {
        this.previewStore = previewStore;
        this.drafts = drafts;
        this.model = model;
        this.json = json;
        this.access =
                new io.agentscope.advisor.access.AccessGuard(
                        new io.agentscope.advisor.access.AccessCatalog(drafts.dataSource()));
    }

    public AgentPreviewStore.PreviewRow createPreview(
            AccountStore.Account account, String draftId, int draftRevision) throws SQLException {
        AgentCreationDraftStore.DraftRow draft =
                drafts.findById(draftId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "draft_unavailable"));
        drafts.requireEditable(draftId);
        if (!AgentCreationDraftStore.canView(
                draft,
                account.userId(),
                access.seesAll(account, io.agentscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "draft_unavailable");
        }
        if (draft.revision() != draftRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision_conflict");
        }
        return previewStore.create(draftId, draftRevision, account.userId());
    }

    public AgentPreviewStore.PreviewRow getPreview(
            AccountStore.Account account, String draftId, String previewId) throws SQLException {
        AgentPreviewStore.PreviewRow preview =
                previewStore
                        .findById(previewId)
                        .filter(p -> p.draftId().equals(draftId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "preview_unavailable"));
        if (!access.seesAll(account, io.agentscope.advisor.access.AccessPoints.AGENT_MANAGE)
                && !account.userId().equals(preview.ownerUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "preview_unavailable");
        }
        return preview;
    }

    public String runPreview(
            AccountStore.Account account, String draftId, String previewId, String input)
            throws SQLException, IOException {
        AgentPreviewStore.PreviewRow preview = getPreview(account, draftId, previewId);
        drafts.requireEditable(draftId);
        AgentCreationDraftStore.DraftRow draft =
                drafts.findById(draftId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "draft_unavailable"));

        List<Map<String, String>> history = parseHistory(preview.historyJson());

        String systemPrompt =
                draft.behavior().isBlank()
                        ? AgentIdentity.PREVIEW_FALLBACK_BEHAVIOR
                        : draft.behavior();

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(systemPrompt).build())
                        .build());

        for (Map<String, String> item : history) {
            String role = item.get("role");
            String content = item.get("content");
            if (role != null && content != null) {
                messages.add(
                        Msg.builder()
                                .role(
                                        "user".equalsIgnoreCase(role)
                                                ? MsgRole.USER
                                                : MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(content).build())
                                .build());
            }
        }

        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(input == null ? "" : input).build())
                        .build());

        String reply =
                ContextModelCalls.auxiliary(model, messages)
                        .reduce(
                                new StringBuilder(),
                                (buf, resp) -> {
                                    List<ContentBlock> blocks = resp.getContent();
                                    if (blocks != null) {
                                        for (ContentBlock b : blocks) {
                                            if (b instanceof TextBlock tb && tb.getText() != null) {
                                                buf.append(tb.getText());
                                            }
                                        }
                                    }
                                    return buf;
                                })
                        .map(StringBuilder::toString)
                        .block(RUN_TIMEOUT);

        String replyText = reply == null ? "" : reply.strip();

        // Update history
        history.add(Map.of("role", "user", "content", input == null ? "" : input));
        history.add(Map.of("role", "assistant", "content", replyText));
        String updatedHistoryJson = json.writeValueAsString(history);

        previewStore.updateHistory(previewId, "idle", updatedHistoryJson);
        return replyText;
    }

    public void deletePreview(AccountStore.Account account, String draftId, String previewId)
            throws SQLException {
        AgentPreviewStore.PreviewRow preview = getPreview(account, draftId, previewId);
        previewStore.delete(previewId);
    }

    private List<Map<String, String>> parseHistory(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return new ArrayList<>();
        try {
            return json.readValue(jsonStr, new TypeReference<List<Map<String, String>>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }
}
