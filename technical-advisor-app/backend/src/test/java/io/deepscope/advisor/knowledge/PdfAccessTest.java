/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.deepscope.advisor.access.AccessCatalog;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class PdfAccessTest {
    @Test
    void previewsTextHtmlAndWordWithoutInventingPagesAndRejectsOversizedText() throws Exception {
        for (String extension : List.of("txt", "html", "docx")) {
            Path file = root.resolve("preview." + extension);
            if ("docx".equals(extension)) {
                try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                        var output = java.nio.file.Files.newOutputStream(file)) {
                    word.createParagraph().createRun().setText("采购规范");
                    var table = word.createTable(2, 2);
                    table.getRow(0).getCell(0).setText("项目");
                    table.getRow(0).getCell(1).setText("金额");
                    table.getRow(1).getCell(0).setText("A");
                    table.getRow(1).getCell(1).setText("12.50");
                    word.write(output);
                }
            } else java.nio.file.Files.writeString(file, "采购规范\n12.50\n第二行");
            var doc =
                    catalog.addDocument(
                            libraryId,
                            "preview." + extension,
                            file.toString(),
                            java.nio.file.Files.size(file));
            String path = "/api/advisor/knowledge/" + libraryId + "/documents/" + doc.documentId();
            mvc.perform(get(path + "/content").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.format")
                                    .value(
                                            "docx".equals(extension)
                                                    ? "markdown"
                                                    : "txt".equals(extension) ? "text" : "html"))
                    .andExpect(
                            jsonPath("$.text").value(org.hamcrest.Matchers.containsString("12.50")))
                    .andExpect(jsonPath("$.pageNumber").doesNotExist());
        }
        Path large = root.resolve("too-large.txt");
        java.nio.file.Files.writeString(large, "a".repeat(1024 * 1024 + 1));
        var doc =
                catalog.addDocument(
                        libraryId, "large.txt", large.toString(), java.nio.file.Files.size(large));
        mvc.perform(
                        get("/api/advisor/knowledge/"
                                        + libraryId
                                        + "/documents/"
                                        + doc.documentId()
                                        + "/content")
                                .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nonPdfPreviewAndPublishedChunksRespectMembershipAndServerPaging() throws Exception {
        Path file = root.resolve("preview.md");
        java.nio.file.Files.writeString(file, "# 采购规范\n\n|项目|金额|\n|---|---|\n|A|12.50|");
        var doc =
                catalog.addDocument(
                        libraryId, "preview.md", file.toString(), java.nio.file.Files.size(file));
        String path = "/api/advisor/knowledge/" + libraryId + "/documents/" + doc.documentId();
        catalog.structures()
                .publish(
                        doc.documentId(),
                        libraryId,
                        List.of(
                                new PdfContent.Chunk("0", "已入库一", null),
                                new PdfContent.Chunk("1", "已入库二", null)),
                        "ready",
                        "",
                        () -> {});
        mvc.perform(get(path + "/content").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("markdown"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("12.50")));
        mvc.perform(get(path + "/chunks?page=2&size=1").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].text").value("已入库二"));
        mvc.perform(get(path + "/pages/1").header("Authorization", token))
                .andExpect(status().isNotFound());
        var other = user(List.of("knowledge.view"));
        mvc.perform(get(path + "/content").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/chunks").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
    }

    @TempDir static Path root;
    @Autowired WebApplicationContext context;
    @Autowired ApiAuthenticationFilter authentication;
    @Autowired AccountStore accounts;
    @Autowired AccessCatalog access;
    @Autowired KnowledgeCatalog catalog;
    MockMvc mvc;
    String token;
    String base;
    String documentId;
    String libraryId;
    String ownerId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("advisor.data-dir", root::toString);
        registry.add("deepscope.openai.api-key", () -> "test-only-key");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:pdf-access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authentication).build();
        var login = user(List.of("knowledge.view", "knowledge.edit"));
        token = "Bearer " + login.token();
        ownerId = login.account().userId();
        libraryId = catalog.create(login.account().userId(), "PDF", "").libraryId();
        var fixture = new PdfDocumentTest();
        fixture.root = root;
        Path file = fixture.pdf(false);
        var document = catalog.addDocument(libraryId, "pages.pdf", file.toString(), 1);
        documentId = document.documentId();
        var structures = catalog.structures();
        var pages = new PdfParser().parse(file, List.of(), count -> {}, page -> {});
        structures.start(documentId, 2, true);
        for (var page : pages) structures.savePage(documentId, page);
        catalog.mark(documentId, "ready", "");
        base = "/api/advisor/knowledge/" + libraryId + "/documents/" + documentId;
    }

    @Test
    void pagesAreServerPagedAndImageUsesAuthenticatedEndpoint() throws Exception {
        mvc.perform(get(base + "/pages?page=2&size=1").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].pageNumber").value(2));
        mvc.perform(get(base + "/pages/1/image").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
        mvc.perform(get(base + "/pages/3/image").header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/info").header("Authorization", token))
                .andExpect(jsonPath("$.filename").value("pages.pdf"));
    }

    @Test
    void cannotReadAnotherOwnersPageOrBorrowDocumentAcrossLibraries() throws Exception {
        var other = user(List.of("knowledge.view"));
        String otherToken = "Bearer " + other.token();
        mvc.perform(get(base + "/pages/1").header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/pages/1/image").header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        var otherLibrary = catalog.create(other.account().userId(), "Other", "");
        String forged =
                "/api/advisor/knowledge/" + otherLibrary.libraryId() + "/documents/" + documentId;
        mvc.perform(get(forged + "/pages/1").header("Authorization", otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryDoesNotScheduleDuplicateWorkAndReadOnlyCannotMutate() throws Exception {
        catalog.mark(documentId, "parsing", "");
        mvc.perform(post(base + "/retry-pages").header("Authorization", token))
                .andExpect(status().isGone());
        var readOnly = user(List.of("knowledge.view"));
        mvc.perform(
                        post(base + "/retry-pages")
                                .header("Authorization", "Bearer " + readOnly.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifiesOnlyVisiblePagesAndDoesNotPretendVisionIsConfigured() throws Exception {
        mvc.perform(
                        post(base + "/pages/1/verify")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"amount?\"}"))
                .andExpect(status().isBadGateway());
        mvc.perform(
                        post(base + "/pages/3/verify")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"amount?\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/pages?page=0&size=1000").header("Authorization", token))
                .andExpect(status().isBadRequest());
        var role = access.createRole("PDF viewer", "pdf-viewer-" + UUID.randomUUID(), 1, "");
        access.replacePermissions(role.roleId(), List.of("knowledge.view"));
        access.replaceGrants(ownerId, List.of(role.roleId()));
        mvc.perform(
                        post(base + "/pages/1/verify")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"amount?\"}"))
                .andExpect(status().isBadGateway());
    }

    private AccountStore.Login user(List<String> permissions) throws Exception {
        String name = "pdf-" + UUID.randomUUID();
        accounts.createUser(name, "test-only-password");
        var login = accounts.login(name, "test-only-password").orElseThrow();
        var role = access.createRole(name, name, 1, "");
        access.replacePermissions(role.roleId(), permissions);
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        return login;
    }
}
