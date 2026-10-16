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
package io.agentscope.advisor.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.access.AccessCatalog;
import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

class AdvisorSkillControllerTest {

    @TempDir Path tempDir;

    private MockMvc mvc;
    private SkillCatalog catalog;
    private TestModel model;
    private AccountStore accounts;
    private String token;
    private String ownerId;
    private ObjectMapper json = new ObjectMapper();
    private DataSource source;

    @BeforeEach
    void setup() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:skill-controller-test-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        source = dataSource;
        accounts =
                new AccountStore(
                        dataSource, java.time.Clock.systemUTC(), java.time.Duration.ofHours(12));
        accounts.bootstrap("root", "root-password");
        catalog = new SkillCatalog(dataSource, tempDir.resolve("skills"));
        AgentStore agents = new AgentStore(dataSource, tempDir.resolve("agents"));
        SkillSync sync = new SkillSync(catalog);
        model = new TestModel();
        SkillDraftService drafts = new SkillDraftService(model);
        AccessGuard access = new AccessGuard(new AccessCatalog(dataSource));

        AdvisorSkillController controller =
                new AdvisorSkillController(catalog, agents, sync, drafts, access);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        token = "Bearer " + accounts.login("root", "root-password").orElseThrow().token();
        ownerId = accounts.authenticate(token.substring(7)).orElseThrow().userId();
    }

    @Test
    void aDatabaseFailureInTheSecondSkillRollsBackTheEntireUpload() throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_skills ADD CONSTRAINT reject_second CHECK (name <> 'z')");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : List.of("a", "z")) {
                zip.putNextEntry(new ZipEntry(name + "/SKILL.md"));
                zip.write(
                        ("---\nname: " + name + "\ndescription: audit\n---\nAudit")
                                .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        try {
            mvc.perform(
                    multipart("/api/advisor/skills")
                            .file(
                                    new MockMultipartFile(
                                            "file",
                                            "batch.zip",
                                            "application/zip",
                                            bytes.toByteArray()))
                            .header("Authorization", token));
        } catch (Exception expected) {
            /* Inspect durable state even when the DB error propagates. */
        }
        assertTrue(catalog.list(ownerId, true).isEmpty());
        Path drafts = tempDir.resolve("skills/drafts");
        if (java.nio.file.Files.exists(drafts)) {
            try (var paths = java.nio.file.Files.walk(drafts)) {
                assertTrue(
                        paths.noneMatch(path -> path.getFileName().toString().equals("SKILL.md")));
            }
        }
    }

    @Test
    void uploadEmptyFileReturnsBadRequestWithStructuredError() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "empty.zip", "application/zip", new byte[0]);
        mvc.perform(multipart("/api/advisor/skills").file(file).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("empty_file"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void managementListUsesPagedEnvelopeAndRejectsInvalidBounds() throws Exception {
        mvc.perform(
                        get("/api/advisor/skills")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("size", "1")
                                .param("q", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/advisor/skills").header("Authorization", token).param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadZipMissingSkillMdReturnsDescriptiveError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("notes.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        MockMultipartFile file =
                new MockMultipartFile("file", "bad.zip", "application/zip", out.toByteArray());
        mvc.perform(multipart("/api/advisor/skills").file(file).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_package"))
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("SKILL.md")));
    }

    @Test
    void uploadMultiSkillZipReturnsBatchCreatedResponse() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("skill-a/SKILL.md"));
            zos.write(
                    "---\nname: skill-a\ndescription: A\n---\n# A"
                            .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("skill-b/SKILL.md"));
            zos.write(
                    "---\nname: skill-b\ndescription: B\n---\n# B"
                            .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        MockMultipartFile file =
                new MockMultipartFile("file", "bundle.zip", "application/zip", out.toByteArray());
        mvc.perform(multipart("/api/advisor/skills").file(file).header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch").value(true))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.skills[0].name").value("skill-a"))
                .andExpect(jsonPath("$.skills[1].name").value("skill-b"));

        List<SkillCatalog.SkillRow> list = catalog.list(ownerId, true);
        assertEquals(2, list.size());
    }

    @Test
    void createManualSkillWithContentSavesDirectlyWithoutCallingModel() throws Exception {
        Map<String, Object> req =
                Map.of(
                        "name", "manual_calc",
                        "description", "计算器辅助技能",
                        "content", "## 步骤\n1. 执行数学计算\n2. 格式化输出");
        mvc.perform(
                        post("/api/advisor/skills")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(req))
                                .header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("manual_calc"))
                .andExpect(jsonPath("$.description").value("计算器辅助技能"));

        assertEquals(0, model.calls.get(), "Model should not be called when content is provided");
        assertTrue(catalog.list("admin", true).isEmpty());
        List<SkillCatalog.SkillRow> list = catalog.list(ownerId, true);
        assertEquals(1, list.size());
        String savedMarkdown =
                java.nio.file.Files.readString(Path.of(list.get(0).draftDir(), "SKILL.md"));
        assertTrue(savedMarkdown.contains("执行数学计算"));
    }

    @Test
    void draftContentEndpointReturnsGeneratedMarkdown() throws Exception {
        model.response = "## 审查步骤\n1. 检查 SQL 注入\n2. 检查越权漏洞";
        Map<String, Object> req =
                Map.of(
                        "name", "security_audit",
                        "description", "安全审计技能");
        mvc.perform(
                        post("/api/advisor/skills/draft-content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(req))
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty())
                .andExpect(
                        jsonPath("$.instructions")
                                .value(org.hamcrest.Matchers.containsString("检查 SQL 注入")));

        assertEquals(1, model.calls.get());
    }

    private static byte[] createZip(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void parseEndpointReturnsParsedMetadataWithoutSavingToCatalog() throws Exception {
        byte[] zipBytes =
                createZip(
                        "my_skill/SKILL.md",
                        "---\nname: parsed_sample\ndescription: 探测解析测试\n---\n\n# 指令内容\n执行分析");
        MockMultipartFile file =
                new MockMultipartFile("file", "parsed_sample.zip", "application/zip", zipBytes);

        mvc.perform(
                        multipart("/api/advisor/skills/parse")
                                .file(file)
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch").value(false))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.skills[0].name").value("parsed_sample"))
                .andExpect(jsonPath("$.skills[0].description").value("探测解析测试"))
                .andExpect(
                        jsonPath("$.skills[0].markdown")
                                .value(org.hamcrest.Matchers.containsString("执行分析")));

        // Verify that catalog remains empty - parse does NOT write to database
        assertTrue(catalog.list(ownerId, true).isEmpty());
    }

    @Test
    void detailAndUpdateEndpointsWorkProperly() throws Exception {
        // Create initial draft
        SkillCatalog.SkillRow created =
                catalog.createDraft(
                        ownerId,
                        "editable_skill",
                        "初始描述",
                        "---\nname: editable_skill\ndescription: 初始描述\n---\n\n# 步骤\n原步骤");

        // 1. GET detail
        mvc.perform(get("/api/advisor/skills/" + created.skillId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("editable_skill"))
                .andExpect(jsonPath("$.description").value("初始描述"))
                .andExpect(
                        jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("原步骤")));

        // 2. PUT update
        Map<String, Object> updateReq =
                Map.of(
                        "name", "updated_skill_name",
                        "description", "更新后的用途描述",
                        "content", "# 新步骤\n执行全面更新并验证");

        mvc.perform(
                        put("/api/advisor/skills/" + created.skillId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(updateReq))
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated_skill_name"))
                .andExpect(jsonPath("$.description").value("更新后的用途描述"));

        // 3. GET detail again to verify file content updated
        mvc.perform(get("/api/advisor/skills/" + created.skillId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated_skill_name"))
                .andExpect(
                        jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("新步骤")));
    }

    @Test
    void batchJsonCreateDraftsSuccessfully() throws Exception {
        Map<String, Object> batchReq =
                Map.of(
                        "skills",
                        List.of(
                                Map.of(
                                        "name",
                                        "batch_skill_a",
                                        "description",
                                        "描述 A",
                                        "content",
                                        "# 指令 A"),
                                Map.of(
                                        "name",
                                        "batch_skill_b",
                                        "description",
                                        "描述 B",
                                        "content",
                                        "# 指令 B")));

        mvc.perform(
                        post("/api/advisor/skills")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(batchReq))
                                .header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch").value(true))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.skills[0].name").value("batch_skill_a"))
                .andExpect(jsonPath("$.skills[1].name").value("batch_skill_b"));

        assertEquals(2, catalog.list(ownerId, true).size());
    }

    @Test
    void administratorCannotReadOrModifyAnotherUsersSkill() throws Exception {
        var skill =
                catalog.createDraft("another-user", "private_skill", "Private", "Private content");
        mvc.perform(get("/api/advisor/skills/" + skill.skillId()).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(
                        put("/api/advisor/skills/" + skill.skillId())
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"changed\",\"description\":\"Changed\"}"))
                .andExpect(status().isNotFound());
        assertTrue(catalog.list(ownerId, true).isEmpty());
        assertEquals("private_skill", catalog.find(skill.skillId()).orElseThrow().name());
    }

    private static final class TestModel implements Model {
        final AtomicInteger calls = new AtomicInteger();
        String response = "默认技能内容";

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(response).build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
