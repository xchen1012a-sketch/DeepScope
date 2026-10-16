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

import io.agentscope.advisor.db.AdvisorSchema;
import java.nio.file.Path;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class AgentOverviewStoreTest {
    @TempDir Path root;

    @Test
    void pagesMergedAgentsAndDraftsWithIndependentVisibilityAndLiteralSearch() throws Exception {
        var ds =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:overview-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(ds);
        var agents = new AgentStore(ds, root);
        var drafts = new AgentCreationDraftStore(ds);
        agents.create("A", "", "own");
        agents.create("B", "", "other");
        drafts.create("own", "manual", null, "", "C", "", "", "[]", "[]", "[]", null, null);
        drafts.create("other", "manual", null, "", "D%_", "", "", "[]", "[]", "[]", null, null);
        var store = new AgentOverviewStore(ds);
        var page = store.page("own", false, false, null, 2, 1);
        assertEquals(2, page.total());
        assertEquals("C", page.items().get(0).name());
        assertEquals(true, page.items().get(0).draft());
        assertEquals(3, store.page("own", true, false, null, 1, 10).total());
        assertEquals(3, store.page("own", false, true, null, 1, 10).total());
        assertEquals(1, store.page("own", true, true, "%_", 1, 10).total());
        assertEquals(0, store.page("own", false, false, "B", 1, 10).total());
        drafts.create(
                "own", "manual", null, "purpose only", "E", "", "", "[]", "[]", "[]", null, null);
        var purposePage = store.page("own", false, false, "purpose only", 1, 10);
        assertEquals(1, purposePage.total());
        assertEquals("purpose only", purposePage.items().get(0).description());
        drafts.create(
                "own",
                "manual",
                null,
                "primary purpose",
                "F",
                "secondary description",
                "",
                "[]",
                "[]",
                "[]",
                null,
                null);
        assertEquals(
                "primary purpose",
                store.page("own", false, false, "primary purpose", 1, 10)
                        .items()
                        .get(0)
                        .description());
    }
}
