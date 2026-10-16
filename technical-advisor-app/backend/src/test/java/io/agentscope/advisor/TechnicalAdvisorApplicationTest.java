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
package io.agentscope.advisor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full application context with a stub model key and a temp data dir. Verifies the
 * wiring that matters: nothing ships with the app — a fresh start provisions no agents at all,
 * and the AG-UI registry stays empty until a user creates one.
 */
@SpringBootTest
class TechnicalAdvisorApplicationTest {

    @TempDir static Path tempDir;

    @DynamicPropertySource
    static void advisorProperties(DynamicPropertyRegistry registry) {
        registry.add("advisor.data-dir", () -> tempDir.toString());
        registry.add("agentscope.openai.api-key", () -> "stub-key-for-context-test");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:advisor-app;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired AgentStore agentStore;

    @Autowired AguiAgentRegistry aguiAgentRegistry;

    @Test
    void emptyInstallationStillHasAToolFreeBasicConversationRuntime() {
        assertTrue(aguiAgentRegistry.hasAgent("basic-chat"));
        var basic =
                (io.agentscope.harness.agent.HarnessAgent)
                        aguiAgentRegistry.getAgent("basic-chat").orElseThrow();
        assertTrue(basic.getToolkit().getToolSchemas().isEmpty());
    }

    @Test
    void freshStartShipsNoAgents() throws SQLException {
        assertTrue(
                agentStore.list().isEmpty(), "no agent may be provisioned into an empty database");
        assertTrue(
                !aguiAgentRegistry.hasAgent("technical-advisor"),
                "the legacy builtin advisor must not come back");
    }
}
