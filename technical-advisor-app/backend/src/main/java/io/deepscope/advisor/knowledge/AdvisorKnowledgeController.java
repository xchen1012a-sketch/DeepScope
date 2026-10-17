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
package io.deepscope.advisor.knowledge;

import io.deepscope.advisor.AdvisorProperties;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Knowledge libraries, document upload, and the shared search used by the test bench. */
@RestController
public class AdvisorKnowledgeController {

    private final KnowledgeCatalog catalog;
    private final DocumentIngest ingest;
    private final ChunkIndex index;
    private final KnowledgeSearch search;
    private final AgentStore agents;
    private final AdvisorProperties properties;
    private final io.deepscope.advisor.access.AccessGuard access;

    public AdvisorKnowledgeController(
            KnowledgeCatalog catalog,
            DocumentIngest ingest,
            ChunkIndex index,
            KnowledgeSearch search,
            AgentStore agents,
            AdvisorProperties properties,
            io.deepscope.advisor.access.AccessGuard access) {
        this.catalog = catalog;
        this.ingest = ingest;
        this.index = index;
        this.search = search;
        this.agents = agents;
        this.properties = properties;
        this.access = access;
    }

    @GetMapping("/api/advisor/knowledge")
    public List<Map<String, Object>> list(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        List<Map<String, Object>> view = new ArrayList<>();
        boolean selection = io.deepscope.advisor.access.WorkspaceAuthorization.selection(request);
        for (KnowledgeCatalog.LibraryRow library :
                catalog.list(
                        io.deepscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        !selection
                                && access.manage(
                                        account,
                                        io.deepscope.advisor.access.AccessPoints.KNOWLEDGE_MANAGE),
                        request.getParameter("q"))) {
            view.add(
                    summary(
                            library,
                            account.userId(),
                            access.manage(
                                    account,
                                    io.deepscope.advisor.access.AccessPoints.KNOWLEDGE_MANAGE)));
        }
        if (selection) view.forEach(item -> item.put("canEdit", false));
        return view;
    }

    @GetMapping(value = "/api/advisor/knowledge", params = "page")
    public io.deepscope.advisor.db.PageResult<Map<String, Object>> page(HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        boolean selection = io.deepscope.advisor.access.WorkspaceAuthorization.selection(request);
        boolean admin =
                !selection
                        && access.manage(
                                account, io.deepscope.advisor.access.AccessPoints.KNOWLEDGE_MANAGE);
        var rows =
                catalog.page(
                        io.deepscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        admin,
                        request.getParameter("q"),
                        io.deepscope.advisor.db.JdbcPage.page(request),
                        io.deepscope.advisor.db.JdbcPage.size(request));
        var items = new ArrayList<Map<String, Object>>();
        for (var library : rows.items()) items.add(summary(library, account.userId(), admin));
        if (selection) items.forEach(item -> item.put("canEdit", false));
        return new io.deepscope.advisor.db.PageResult<>(
                items, rows.page(), rows.size(), rows.total(), rows.totalPages());
    }

    public record DocumentPage(
            List<Map<String, Object>> items,
            int page,
            int size,
            long total,
            long totalPages,
            int parsingCount) {}

    @GetMapping("/api/advisor/knowledge/{libraryId}/documents")
    public ResponseEntity<DocumentPage> documentPage(
            @PathVariable String libraryId, HttpServletRequest request) throws SQLException {
        if (visible(libraryId, request) == null) return ResponseEntity.notFound().build();
        var page =
                catalog.pageDocuments(
                                libraryId,
                                request.getParameter("q"),
                                request.getParameter("status"),
                                io.deepscope.advisor.db.JdbcPage.page(request),
                                io.deepscope.advisor.db.JdbcPage.size(request))
                        .map(AdvisorKnowledgeController::documentView);
        return ResponseEntity.ok(
                new DocumentPage(
                        page.items(),
                        page.page(),
                        page.size(),
                        page.total(),
                        page.totalPages(),
                        catalog.parsingCount(libraryId)));
    }

    @PostMapping("/api/advisor/knowledge")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        try {
            KnowledgeCatalog.LibraryRow library =
                    catalog.create(account.userId(), text(body, "name"), text(body, "description"));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            summary(
                                    library,
                                    account.userId(),
                                    access.manage(
                                            account,
                                            io.deepscope.advisor.access.AccessPoints
                                                    .KNOWLEDGE_MANAGE)));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/api/advisor/knowledge/{libraryId}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String libraryId, HttpServletRequest request) throws SQLException {
        var library = visible(libraryId, request);
        if (library == null) {
            return ResponseEntity.notFound().build();
        }
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        Map<String, Object> view =
                summary(
                        library,
                        account.userId(),
                        access.manage(
                                account,
                                io.deepscope.advisor.access.AccessPoints.KNOWLEDGE_MANAGE));
        List<Map<String, Object>> documents = new ArrayList<>();
        if (!"false".equals(request.getParameter("includeDocuments"))) {
            for (KnowledgeCatalog.DocumentRow document : catalog.documents(libraryId)) {
                documents.add(documentView(document));
            }
        }
        view.put("documents", documents);
        return ResponseEntity.ok(view);
    }

    @PatchMapping("/api/advisor/knowledge/{libraryId}")
    public ResponseEntity<Void> settings(
            @PathVariable String libraryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var library = editable(libraryId, request);
        if (library == null) {
            return missing(libraryId, request);
        }
        try {
            catalog.updateSettings(
                    libraryId,
                    number(body, "chunkSize", library.chunkSize()),
                    number(body, "overlap", library.overlap()),
                    number(body, "recallLimit", library.recallLimit()),
                    decimal(body, "scoreThreshold", library.scoreThreshold()));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/api/advisor/knowledge/{libraryId}")
    public ResponseEntity<Void> delete(@PathVariable String libraryId, HttpServletRequest request)
            throws SQLException, IOException {
        var library = editable(libraryId, request);
        if (library == null) {
            return missing(libraryId, request);
        }
        catalog.deleteLibrary(libraryId);
        index.deleteLibrary(libraryId);
        Path dir = properties.knowledgeDir().resolve(libraryId);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted((left, right) -> right.compareTo(left)).forEach(this::deleteQuietly);
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(
            value = "/api/advisor/knowledge/{libraryId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable String libraryId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request)
            throws SQLException, IOException {
        var library = editable(libraryId, request);
        if (library == null) {
            return missing(libraryId, request);
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String filename = DocumentTexts.safeName(file.getOriginalFilename());
        Path dir = properties.knowledgeDir().resolve(libraryId);
        Files.createDirectories(dir);
        Path stored = dir.resolve(java.util.UUID.randomUUID() + "-" + filename);
        Files.write(stored, file.getBytes());
        KnowledgeCatalog.DocumentRow document =
                catalog.addDocument(libraryId, filename, stored.toString(), file.getSize());
        ingest.submit(document.documentId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documentView(document));
    }

    @PostMapping("/api/advisor/knowledge/{libraryId}/documents/{documentId}/reprocess")
    public ResponseEntity<Void> reprocess(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException {
        if (editable(libraryId, request) == null) {
            return missing(libraryId, request);
        }
        KnowledgeCatalog.DocumentRow document = catalog.findDocument(documentId).orElse(null);
        if (document == null || !libraryId.equals(document.libraryId())) {
            return ResponseEntity.notFound().build();
        }
        if (ingest.isActive(documentId) || !catalog.requestReprocess(documentId))
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        ingest.submit(documentId);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/api/advisor/knowledge/{libraryId}/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException, IOException {
        if (editable(libraryId, request) == null) {
            return missing(libraryId, request);
        }
        KnowledgeCatalog.DocumentRow document = catalog.findDocument(documentId).orElse(null);
        if (document == null || !libraryId.equals(document.libraryId())) {
            return ResponseEntity.notFound().build();
        }
        catalog.deleteDocument(documentId);
        index.deleteDocument(libraryId, documentId);
        Files.deleteIfExists(Path.of(document.storagePath()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/advisor/knowledge/{libraryId}/search")
    public ResponseEntity<?> search(
            @PathVariable String libraryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        if (visible(libraryId, request) == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            List<Map<String, Object>> hits = new ArrayList<>();
            for (KnowledgeSearch.Found found :
                    search.search(List.of(libraryId), text(body, "query"))) {
                hits.add(hit(found));
            }
            return ResponseEntity.ok(hits);
        } catch (IllegalStateException | IllegalArgumentException error) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/api/advisor/agents/{agentId}/knowledge-selection")
    public ResponseEntity<Map<String, Object>> agentSelection(
            @PathVariable String agentId, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (agents.find(agentId)
                .filter(
                        agent ->
                                AgentStore.canView(
                                        agent,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .AGENT_MANAGE)))
                .isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("libraryIds", catalog.agentSelection(agentId)));
    }

    @PutMapping("/api/advisor/agents/{agentId}/knowledge-selection")
    public ResponseEntity<Void> replaceAgentSelection(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent =
                agents.find(agentId)
                        .filter(
                                row ->
                                        AgentStore.canView(
                                                row,
                                                account.userId(),
                                                access.manage(
                                                        account,
                                                        io.deepscope.advisor.access.AccessPoints
                                                                .AGENT_MANAGE)))
                        .orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(
                agent,
                account.userId(),
                access.manage(account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            String owner = agent.ownerUserId() == null ? account.userId() : agent.ownerUserId();
            catalog.replaceAgentSelection(agentId, checked(body, owner, false));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    private List<String> checked(Map<String, Object> body, String ownerUserId, boolean admin)
            throws SQLException {
        List<String> libraryIds = new ArrayList<>();
        if (body.get("libraryIds") instanceof List<?> items) {
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                KnowledgeCatalog.LibraryRow library = catalog.find(item.toString()).orElse(null);
                if (library == null || !KnowledgeCatalog.canView(library, ownerUserId, admin)) {
                    throw new IllegalArgumentException("Library is not visible");
                }
                libraryIds.add(library.libraryId());
            }
        }
        return libraryIds;
    }

    private KnowledgeCatalog.LibraryRow visible(String libraryId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(libraryId)
                .filter(
                        library ->
                                KnowledgeCatalog.canView(
                                        library,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .KNOWLEDGE_MANAGE)))
                .orElse(null);
    }

    private KnowledgeCatalog.LibraryRow editable(String libraryId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(libraryId)
                .filter(
                        library ->
                                KnowledgeCatalog.canView(
                                        library,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .KNOWLEDGE_MANAGE)))
                .filter(
                        library ->
                                KnowledgeCatalog.canEdit(
                                        library,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .KNOWLEDGE_MANAGE)))
                .orElse(null);
    }

    private ResponseEntity<Void> missing(String libraryId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(libraryId)
                        .filter(
                                library ->
                                        KnowledgeCatalog.canView(
                                                library,
                                                account.userId(),
                                                access.manage(
                                                        account,
                                                        io.deepscope.advisor.access.AccessPoints
                                                                .KNOWLEDGE_MANAGE)))
                        .isPresent()
                ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                : ResponseEntity.notFound().build();
    }

    private Map<String, Object> summary(
            KnowledgeCatalog.LibraryRow library, String userId, boolean admin) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("libraryId", library.libraryId());
        view.put("name", library.name());
        view.put("description", library.description());
        view.put("chunkSize", library.chunkSize());
        view.put("overlap", library.overlap());
        view.put("recallLimit", library.recallLimit());
        view.put("scoreThreshold", library.scoreThreshold());
        view.putAll(catalog.statusCounts(library.libraryId()));
        var permissions = access.permissionsOf(userId);
        view.put(
                "canEdit",
                (permissions == null
                                || permissions.contains("knowledge.edit")
                                || permissions.contains(
                                        io.deepscope.advisor.access.AccessPoints.KNOWLEDGE_MANAGE))
                        && KnowledgeCatalog.canEdit(library, userId, admin));
        return view;
    }

    private static Map<String, Object> documentView(KnowledgeCatalog.DocumentRow document) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("documentId", document.documentId());
        view.put("filename", document.filename());
        view.put("sizeBytes", document.sizeBytes());
        view.put("status", document.status());
        view.put("failure", document.failure());
        view.put("createdAt", document.createdAt());
        view.put("pageCount", document.pageCount());
        view.put("processedPages", document.processedPages());
        view.put("failedPages", document.failedPages());
        view.put("stage", document.stage());
        view.put("embeddedChunks", document.embeddedChunks());
        view.put("totalChunks", document.totalChunks());
        return view;
    }

    private static Map<String, Object> hit(KnowledgeSearch.Found found) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("library", found.libraryName());
        view.put("document", found.filename());
        view.put("passage", found.passage());
        view.put("score", found.score());
        view.put("libraryId", found.libraryId());
        view.put("documentId", found.documentId());
        view.put("source", found.source());
        view.put("context", found.context());
        view.put("vectorScore", found.vectorScore());
        view.put("rankingMethod", found.rankingMethod());
        view.put("warnings", found.warnings());
        return view;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The library row is already gone; a leftover file can be removed later.
        }
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString();
        return value.isBlank() ? null : value;
    }

    private static int number(Map<String, Object> body, String key, int fallback) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static double decimal(Map<String, Object> body, String key, double fallback) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }
}
