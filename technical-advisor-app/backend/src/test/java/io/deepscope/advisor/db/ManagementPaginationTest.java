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

package io.deepscope.advisor.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.knowledge.KnowledgeCatalog;
import io.deepscope.advisor.model.ModelCatalog;
import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.advisor.tool.ToolImport;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ManagementPaginationTest {
    private SimpleDriverDataSource dataSource;

    @BeforeEach
    void setup() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:paging-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
    }

    @Test
    void toolSearchPermissionAndOwnershipAreAppliedBeforePaging() throws Exception {
        var catalog = new ToolCatalog(dataSource, new ObjectMapper());
        for (String owner : List.of("me", "other"))
            for (String name : List.of("a%_", "b%_", "c")) {
                catalog.importTools(
                        owner,
                        List.of(
                                new ToolImport.ImportedTool(
                                        name, "Match", "{}", "http", "GET", "https://example.com")),
                        "ask");
            }
        var page = catalog.pageTools("me", false, "%_", "ask", 2, 1);
        assertEquals(2, page.total());
        assertEquals("b%_", page.items().get(0).name());
        assertEquals("me", page.items().get(0).ownerUserId());
        assertEquals(0, catalog.pageTools("me", false, "match", "deny", 1, 10).total());
        assertEquals(2, catalog.pageTools("me", true, "%_", "ask", 1, 1).total());
    }

    @Test
    void toolOriginFilterNarrowsSelectorsWithoutTouchingOtherPages() throws Exception {
        var catalog = new ToolCatalog(dataSource, new ObjectMapper());
        catalog.importTools(
                "me",
                List.of(
                        new ToolImport.ImportedTool(
                                "http-one", "", "{}", "http", "GET", "https://example.com")),
                "ask");
        var server =
                catalog.insertServer(
                        new io.deepscope.advisor.tool.McpEndpointParser.McpEndpoint(
                                "service",
                                "stdio",
                                "npx",
                                List.of("mcp"),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "me",
                        "connected",
                        "");
        catalog.approveMcpTool(
                server,
                new io.deepscope.advisor.tool.ToolCatalog.DiscoveredTool("mcp-one", "", "{}"),
                "ask");
        assertEquals(
                1,
                catalog.pageTools("me", false, null, null, List.of("http", "declared"), 1, 10)
                        .total());
        assertEquals(1, catalog.pageTools("me", false, null, null, List.of("mcp"), 1, 10).total());
        assertEquals(2, catalog.pageTools("me", false, null, null, List.of(), 1, 10).total());
    }

    @Test
    void knowledgeAndDocumentPagesStayScopedAndBounded() throws Exception {
        var catalog = new KnowledgeCatalog(dataSource);
        var mine = catalog.create("me", "Mine", "target");
        var theirs = catalog.create("other", "Theirs", "target");
        catalog.addDocument(mine.libraryId(), "a.txt", "/a", 1);
        catalog.addDocument(mine.libraryId(), "b.txt", "/b", 1);
        catalog.addDocument(theirs.libraryId(), "a.txt", "/c", 1);
        assertEquals(1, catalog.page("me", false, "target", 1, 1).total());
        assertEquals(2, catalog.page("me", true, "target", 1, 1).total());
        var page = catalog.pageDocuments(mine.libraryId(), ".txt", "parsing", 2, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.items().size());
        assertEquals(mine.libraryId(), page.items().get(0).libraryId());
        assertEquals(0, catalog.pageDocuments(mine.libraryId(), null, "ready", 1, 10).total());
    }

    @Test
    void documentPageReportsPendingWorkOutsideCurrentFilterAndRespectsVisibility()
            throws Exception {
        var catalog = new KnowledgeCatalog(dataSource);
        var mine = catalog.create("me", "Mine", "");
        var theirs = catalog.create("other", "Theirs", "");
        var pending = catalog.addDocument(mine.libraryId(), "pending.txt", "/a", 1);
        catalog.addDocument(theirs.libraryId(), "foreign.txt", "/b", 1);
        var controller =
                new io.deepscope.advisor.knowledge.AdvisorKnowledgeController(
                        catalog,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new io.deepscope.advisor.access.AccessGuard(
                                new io.deepscope.advisor.access.AccessCatalog(dataSource)));
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                controller)
                        .build();
        var account = new io.deepscope.advisor.auth.AccountStore.Account("me", "me", "user", 0);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/knowledge/" + mine.libraryId())
                                .requestAttr(
                                        io.deepscope.advisor.auth.AccountStore.Account.class
                                                .getName(),
                                        account)
                                .param("includeDocuments", "false"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.documents.length()")
                                .value(0))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.parsingCount")
                                .value(1));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/knowledge/" + mine.libraryId() + "/documents")
                                .requestAttr(
                                        io.deepscope.advisor.auth.AccountStore.Account.class
                                                .getName(),
                                        account)
                                .param("page", "1")
                                .param("q", "missing")
                                .param("status", "ready"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.total")
                                .value(0))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.parsingCount")
                                .value(1));
        catalog.mark(pending.documentId(), "ready", "");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/knowledge/" + mine.libraryId() + "/documents")
                                .requestAttr(
                                        io.deepscope.advisor.auth.AccountStore.Account.class
                                                .getName(),
                                        account)
                                .param("page", "1")
                                .param("status", "ready"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.total")
                                .value(1))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.parsingCount")
                                .value(0));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/knowledge/"
                                                + theirs.libraryId()
                                                + "/documents")
                                .requestAttr(
                                        io.deepscope.advisor.auth.AccountStore.Account.class
                                                .getName(),
                                        account)
                                .param("page", "1"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void modelProviderAndKindFilterBeforePagingWithoutChangingDefault() throws Exception {
        var catalog = new ModelCatalog(dataSource);
        catalog.insertCurrent(
                new ModelCatalog.EndpointDraft(
                        "chat", "custom", "a", "https://example.com", "fake"),
                null);
        var current =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "b", "https://example.com", "fake"),
                        null);
        catalog.insertCurrent(
                new ModelCatalog.EndpointDraft(
                        "embedding", "custom", "a", "https://example.com", "fake"),
                3);
        assertEquals(2, catalog.page("chat", "custom", null, 2, 1).total());
        assertEquals(1, catalog.page("chat", "custom", "a", 1, 10).total());
        assertEquals(0, catalog.page("chat", "other", null, 1, 10).total());
        assertEquals(current.endpointId(), catalog.current("chat").orElseThrow().endpointId());
    }

    @Test
    void skillStatusAndOwnerAreFilteredInSql() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO"
                        + " advisor_skills(skill_id,name,status,origin,owner_user_id,draft_dir,created_at,updated_at)"
                        + " VALUES"
                        + " ('a','Alpha','draft','upload','me','/a',1,1),('b','Beta','published','upload','me','/b',2,2),('c','Gamma','draft','upload','other','/c',3,3)");
        }
        var catalog = new SkillCatalog(dataSource, Path.of("/tmp/paging-skills"));
        assertEquals(1, catalog.page("me", false, null, "draft", 1, 1).total());
        assertEquals(1, catalog.page("me", true, null, "draft", 1, 1).total());
        assertEquals(0, catalog.page("me", false, "Gamma", null, 1, 10).total());
    }
}
