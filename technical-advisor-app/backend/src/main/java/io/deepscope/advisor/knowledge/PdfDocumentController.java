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

import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.access.AccessPoints;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.db.JdbcPage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Each page endpoint checks the library, document membership and the requested operation. */
@RestController
@RequestMapping("/api/advisor/knowledge/{libraryId}/documents/{documentId}")
public final class PdfDocumentController {
    private final KnowledgeCatalog catalog;
    private final DocumentStructureStore structures;
    private final DocumentIngest ingest;
    private final PdfParser parser;
    private final KnowledgeSearch search;
    private final AccessGuard access;

    public PdfDocumentController(
            KnowledgeCatalog catalog,
            DocumentIngest ingest,
            PdfParser parser,
            KnowledgeSearch search,
            AccessGuard access) {
        this.catalog = catalog;
        this.structures = catalog.structures();
        this.ingest = ingest;
        this.parser = parser;
        this.search = search;
        this.access = access;
    }

    @GetMapping("/pages")
    public ResponseEntity<?> pages(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException {
        if (visible(libraryId, documentId, request) == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                structures.page(documentId, JdbcPage.page(request), JdbcPage.size(request)));
    }

    @GetMapping("/content")
    public ResponseEntity<?> content(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException {
        var document = visibleDocument(libraryId, documentId, request);
        if (document == null || "pdf".equals(DocumentTexts.extension(document.filename())))
            return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, no-store")
                    .body(
                            DocumentTexts.preview(
                                    Path.of(document.storagePath()), document.filename()));
        } catch (Exception error) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("message", "内容预览失败：文件损坏、编码不支持或正文超过 1MiB 限制"));
        }
    }

    @GetMapping("/chunks")
    public ResponseEntity<?> chunks(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException {
        if (visibleDocument(libraryId, documentId, request) == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(
                        structures.chunks(
                                documentId, JdbcPage.page(request), JdbcPage.size(request)));
    }

    @GetMapping("/info")
    public ResponseEntity<?> info(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws SQLException {
        var document = visible(libraryId, documentId, request);
        return document == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(
                        Map.of(
                                "filename",
                                document.filename(),
                                "pageCount",
                                document.pageCount(),
                                "documentId",
                                documentId));
    }

    @GetMapping("/pages/{pageNumber}")
    public ResponseEntity<?> page(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            @PathVariable int pageNumber,
            HttpServletRequest request)
            throws SQLException {
        if (visible(libraryId, documentId, request) == null)
            return ResponseEntity.notFound().build();
        return structures
                .find(documentId, pageNumber)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/pages/{pageNumber}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<?> image(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            @PathVariable int pageNumber,
            HttpServletRequest request)
            throws SQLException {
        var document = visible(libraryId, documentId, request);
        if (document == null || pageNumber < 1 || pageNumber > document.pageCount())
            return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "private, no-store")
                    .body(parser.image(Path.of(document.storagePath()), pageNumber));
        } catch (Exception error) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PostMapping("/retry-pages")
    public ResponseEntity<?> retry(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            HttpServletRequest request)
            throws Exception {
        var document = visible(libraryId, documentId, request);
        if (document == null) return ResponseEntity.notFound().build();
        if (!editable(libraryId, request)) return ResponseEntity.status(403).build();
        return ResponseEntity.status(410).body(Map.of("message", "逐页重试已移除，请使用整篇重新处理"));
    }

    @PostMapping("/pages/{pageNumber}/verify")
    public ResponseEntity<?> verify(
            @PathVariable String libraryId,
            @PathVariable String documentId,
            @PathVariable int pageNumber,
            @RequestBody Map<String, String> body,
            HttpServletRequest request)
            throws SQLException {
        if (visible(libraryId, documentId, request) == null
                || structures.find(documentId, pageNumber).isEmpty())
            return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(
                    Map.of(
                            "verification",
                            search.verifyPage(libraryId, documentId, pageNumber, body.get("query")),
                            "method",
                            "vision",
                            "pageNumber",
                            pageNumber));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", "复核问题为空或超限"));
        } catch (Exception error) {
            return ResponseEntity.status(502).body(Map.of("message", "页面复核未完成，请检查 PDF 视觉模型配置"));
        }
    }

    private KnowledgeCatalog.DocumentRow visible(
            String libraryId, String documentId, HttpServletRequest request) throws SQLException {
        var document = visibleDocument(libraryId, documentId, request);
        return document != null && "pdf".equals(DocumentTexts.extension(document.filename()))
                ? document
                : null;
    }

    private KnowledgeCatalog.DocumentRow visibleDocument(
            String libraryId, String documentId, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var library = catalog.find(libraryId).orElse(null);
        if (library == null
                || !KnowledgeCatalog.canView(
                        library,
                        account.userId(),
                        access.manage(account, AccessPoints.KNOWLEDGE_MANAGE))) return null;
        return catalog.findDocument(documentId)
                .filter(document -> libraryId.equals(document.libraryId()))
                .orElse(null);
    }

    private boolean editable(String libraryId, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(libraryId)
                .filter(
                        library ->
                                KnowledgeCatalog.canEdit(
                                        library,
                                        account.userId(),
                                        access.manage(account, AccessPoints.KNOWLEDGE_MANAGE)))
                .isPresent();
    }
}
