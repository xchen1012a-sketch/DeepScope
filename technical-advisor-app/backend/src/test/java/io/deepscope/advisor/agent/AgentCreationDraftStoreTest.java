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
package io.deepscope.advisor.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Task 1: Draft storage, idempotency, revision conflict, visibility rules,
 * and physical isolation from official AgentStore.
 */
class AgentCreationDraftStoreTest {

    @TempDir Path agentsRoot;

    private DataSource dataSource;
    private AgentCreationDraftStore draftStore;
    private AgentStore agentStore;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:draft-store-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        draftStore = new AgentCreationDraftStore(dataSource);
        agentStore = new AgentStore(dataSource, agentsRoot);
    }

    @Test
    void createDraftAndPersistInitialSnapshot() throws Exception {
        AgentCreationDraftStore.DraftRow draft =
                draftStore.create(
                        "user-1",
                        "describe",
                        null,
                        "帮我写会议纪要",
                        "会议助手",
                        "专注整理待办",
                        "严格按照会议内容总结，不主观编造",
                        "[\"tool-1\"]",
                        "[\"skill-1\"]",
                        "[]",
                        null,
                        null);

        assertNotNull(draft.draftId());
        assertEquals("user-1", draft.ownerUserId());
        assertEquals(1, draft.revision());
        assertEquals("帮我写会议纪要", draft.purpose());
        assertEquals("会议助手", draft.name());

        AgentCreationDraftStore.DraftRow loaded =
                draftStore.findById(draft.draftId()).orElseThrow();
        assertEquals(draft.draftId(), loaded.draftId());
        assertEquals(1, loaded.revision());
        assertEquals("会议助手", loaded.name());
        assertEquals("严格按照会议内容总结，不主观编造", loaded.behavior());
    }

    @Test
    void idempotentCreationWithSameKeyReturnsExistingDraft() throws Exception {
        String key = "req-key-123";
        String hash = "hash-abc";

        AgentCreationDraftStore.DraftRow first =
                draftStore.create(
                        "user-1",
                        "describe",
                        null,
                        "任务一",
                        "助手一",
                        "简介",
                        "行为说明",
                        "[]",
                        "[]",
                        "[]",
                        key,
                        hash);

        AgentCreationDraftStore.DraftRow duplicate =
                draftStore.create(
                        "user-1",
                        "describe",
                        null,
                        "任务一",
                        "助手一",
                        "简介",
                        "行为说明",
                        "[]",
                        "[]",
                        "[]",
                        key,
                        hash);

        assertEquals(first.draftId(), duplicate.draftId(), "Same key must return the same draft");
        assertEquals(first.revision(), duplicate.revision());
    }

    @Test
    void idempotentCreationWithSameKeyDifferentHashThrowsConflict() throws Exception {
        String key = "req-key-conflict";

        draftStore.create(
                "user-1", "describe", null, "任务A", "助手A", "", "", "[]", "[]", "[]", key, "hash-A");

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                draftStore.create(
                                        "user-1",
                                        "describe",
                                        null,
                                        "任务B（内容改变）",
                                        "助手B",
                                        "",
                                        "",
                                        "[]",
                                        "[]",
                                        "[]",
                                        key,
                                        "hash-B"));
        assertEquals(
                409, ex.getStatusCode().value(), "Conflicting hash with same key must return 409");
    }

    @Test
    void updateSucceedsWithMatchingRevisionAndIncrementsRevision() throws Exception {
        AgentCreationDraftStore.DraftRow draft =
                draftStore.create(
                        "user-1",
                        "describe",
                        null,
                        "初版用途",
                        "初版名称",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        AgentCreationDraftStore.DraftRow updated =
                draftStore.update(
                        draft.draftId(),
                        1,
                        new AgentCreationDraftStore.DraftUpdate(
                                "新用途",
                                "新名称",
                                "新简介",
                                "新工作要求",
                                "[\"tool-2\"]",
                                "[\"skill-2\"]",
                                "[]"));

        assertEquals(2, updated.revision());
        assertEquals("新名称", updated.name());
        assertEquals("新工作要求", updated.behavior());
    }

    @Test
    void updateFailsWith409ConflictWhenRevisionIsStale() throws Exception {
        AgentCreationDraftStore.DraftRow draft =
                draftStore.create(
                        "user-1",
                        "describe",
                        null,
                        "初版用途",
                        "初版名称",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        // First update advances revision to 2
        draftStore.update(
                draft.draftId(),
                1,
                new AgentCreationDraftStore.DraftUpdate("第一次更新", "名称2", "", "", "[]", "[]", "[]"));

        // Second update still using stale revision 1 must throw 409
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                draftStore.update(
                                        draft.draftId(),
                                        1,
                                        new AgentCreationDraftStore.DraftUpdate(
                                                "并发更新", "名称冲突", "", "", "[]", "[]", "[]")));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void visibilityFollowsOwnerAndAdminRules() throws Exception {
        draftStore.create(
                "user-a", "describe", null, "A用途", "A名称", "", "", "[]", "[]", "[]", null, null);
        draftStore.create(
                "user-b", "describe", null, "B用途", "B名称", "", "", "[]", "[]", "[]", null, null);

        List<AgentCreationDraftStore.DraftRow> userAVisible =
                draftStore.listVisible("user-a", false);
        assertEquals(1, userAVisible.size());
        assertEquals("user-a", userAVisible.get(0).ownerUserId());

        List<AgentCreationDraftStore.DraftRow> userBVisible =
                draftStore.listVisible("user-b", false);
        assertEquals(1, userBVisible.size());
        assertEquals("user-b", userBVisible.get(0).ownerUserId());

        List<AgentCreationDraftStore.DraftRow> adminVisible = draftStore.listVisible("admin", true);
        assertEquals(2, adminVisible.size());
    }

    @Test
    void sameNameAllowedAcrossDrafts() throws Exception {
        AgentCreationDraftStore.DraftRow d1 =
                draftStore.create(
                        "user-a",
                        "describe",
                        null,
                        "用途1",
                        "重名助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        AgentCreationDraftStore.DraftRow d2 =
                draftStore.create(
                        "user-a",
                        "describe",
                        null,
                        "用途2",
                        "重名助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        assertNotEquals(d1.draftId(), d2.draftId());
        assertEquals("重名助手", d1.name());
        assertEquals("重名助手", d2.name());
    }

    @Test
    void draftsDoNotAppearInOfficialAgentStoreOrRegistry() throws Exception {
        draftStore.create(
                "user-1", "describe", null, "测试用途", "草稿助手", "", "", "[]", "[]", "[]", null, null);

        // Official AgentStore must remain empty
        List<AgentStore.AgentRow> officialAgents = agentStore.list();
        assertTrue(officialAgents.isEmpty(), "Drafts must never appear in official AgentStore");
    }
}
