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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.advisor.db.AdvisorSchema;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Agent rows: per-owner visibility, builtin upsert, edit rights and delete guards (TDS 3.4). */
class AgentStoreTest {

    @TempDir Path agentsRoot;

    private AgentStore agents;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:agent-store-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        agents = new AgentStore(dataSource, agentsRoot);
    }

    @Test
    void personalAgentRowsCarryIsolatedDirectoriesUnderTheAgentsRoot() throws Exception {
        AgentStore.AgentRow agent = agents.create("尽调助手", "专注仓库尽调", "user-1");

        assertEquals(AgentStore.ORIGIN_PERSONAL, agent.origin());
        assertEquals("user-1", agent.ownerUserId());
        assertTrue(
                agent.workspaceDir().startsWith(agentsRoot.toString()),
                "workspace must stay under the agents root");
        assertTrue(agent.workspaceDir().endsWith("workspace"), "unexpected workspace layout");
        assertTrue(agent.stateDir().endsWith("state"), "unexpected state layout");
        assertTrue(agents.find(agent.agentId()).isPresent());
    }

    @Test
    void visibilityShowsOwnPersonalAgentsAndEverythingToAdmins() throws Exception {
        agents.create("A 的助手", "", "user-a");
        agents.create("B 的助手", "", "user-b");

        assertEquals(1, agents.listVisible("user-a", false).size());
        assertEquals(1, agents.listVisible("user-b", false).size());
        assertEquals(2, agents.listVisible("admin", true).size());
        assertEquals(0, agents.listVisible("user-c", false).size());
    }

    @Test
    void canEditGivesPersonalRowsToOwnerOrAdmin() throws Exception {
        AgentStore.AgentRow personal = agents.create("助手", "", "user-a");

        assertTrue(AgentStore.canEdit(personal, "user-a", false));
        assertFalse(AgentStore.canEdit(personal, "user-b", false));
        assertTrue(AgentStore.canEdit(personal, "admin", true));
    }

    @Test
    void updateEditsDisplayFieldsAndDeleteRemovesRows() throws Exception {
        AgentStore.AgentRow personal = agents.create("助手", "旧描述", "user-a");

        assertTrue(agents.update(personal.agentId(), "新名字", "新描述"));
        assertEquals("新名字", agents.find(personal.agentId()).orElseThrow().name());
        assertEquals("新描述", agents.find(personal.agentId()).orElseThrow().description());

        assertTrue(agents.delete(personal.agentId()));
        assertTrue(agents.find(personal.agentId()).isEmpty());
    }

    @Test
    void createRejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> agents.create("  ", "", "user-1"));
    }
}
