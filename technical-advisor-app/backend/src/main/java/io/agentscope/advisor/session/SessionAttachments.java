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
package io.agentscope.advisor.session;

import io.agentscope.advisor.knowledge.DocumentTexts;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.PDFReader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TableFormat;
import io.agentscope.core.rag.reader.WordReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.web.multipart.MultipartFile;

/**
 * Parses visitor-uploaded chat attachments (images, ZIP archives, Office documents, PDF, Markdown,
 * and source/config files) into structured metadata and extracted text for the advisor session.
 */
public final class SessionAttachments {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARS = 24_000;
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final long MAX_ZIP_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final int MAX_ZIP_SNIPPET_CHARS = 4_000;

    private static final Pattern QUOTED_PATH_PATTERN =
            Pattern.compile("[\"'`“‘]([^\"'`”’\\r\\n]{2,512})[\"'`”’]");

    private static final Pattern INLINE_PATH_START_PATTERN =
            Pattern.compile(
                    "(?:@?(?:file://[^\\s\"'`”’]+|~/[^\\s\"'`”’]+|/(?:Users|private|tmp|var|Volumes|opt|home|etc)/[^\\s\"'`”’]+|[A-Za-z]:[\\\\/][^\\s\"'`”’]+|uploads/[A-Za-z0-9_\\-]+/[^\\s\"'`”’]+))");

    private static final Map<String, String> IMAGE_MIMES =
            Map.of(
                    "png", "image/png",
                    "jpg", "image/jpeg",
                    "jpeg", "image/jpeg",
                    "webp", "image/webp",
                    "gif", "image/gif");

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of(
                    "md",
                    "markdown",
                    "txt",
                    "log",
                    "csv",
                    "tsv",
                    "json",
                    "yaml",
                    "yml",
                    "xml",
                    "toml",
                    "ini",
                    "properties",
                    "env",
                    "sql",
                    "sh",
                    "bash",
                    "zsh",
                    "java",
                    "kt",
                    "py",
                    "ts",
                    "tsx",
                    "js",
                    "jsx",
                    "go",
                    "rs",
                    "c",
                    "cpp",
                    "h",
                    "hpp",
                    "cs",
                    "rb",
                    "php",
                    "swift",
                    "css",
                    "scss",
                    "vue",
                    "svelte",
                    "dockerfile",
                    "gradle");

    private static final Set<String> TIKA_EXTENSIONS =
            Set.of("doc", "docx", "ppt", "pptx", "xls", "xlsx", "pdf", "rtf");

    private static final Set<String> HTML_EXTENSIONS = Set.of("html", "htm");

    private SessionAttachments() {}

    public record AttachmentContent(Path file, String mimeType, byte[] bytes) {}

    public static Optional<AttachmentContent> readAttachmentContent(
            String attachmentId, Path workspaceDir) throws IOException {
        if (attachmentId == null || !attachmentId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            return Optional.empty();
        }
        Path uploadsRoot = workspaceDir.resolve("uploads").normalize();
        Path dir = uploadsRoot.resolve(attachmentId).normalize();
        if (!dir.startsWith(uploadsRoot) || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(dir)) {
            Optional<Path> candidate =
                    children.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().endsWith(".extracted.txt"))
                            .findFirst();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            Path file = candidate.get();
            String ext = extension(file.getFileName().toString());
            String mimeType =
                    IMAGE_MIMES.getOrDefault(
                            ext,
                            Optional.ofNullable(Files.probeContentType(file))
                                    .orElse("application/octet-stream"));
            return Optional.of(new AttachmentContent(file, mimeType, Files.readAllBytes(file)));
        }
    }

    public static Map<String, Object> process(MultipartFile file, Path workspaceDir)
            throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("附件内容为空");
        }
        return processBytes(file.getOriginalFilename(), file.getBytes(), workspaceDir);
    }

    public static Map<String, Object> processBytes(
            String originalFilename, byte[] bytes, Path workspaceDir) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("附件内容为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("单个附件大小不能超过 20MB");
        }
        String safeName = DocumentTexts.safeName(originalFilename);
        String ext = extension(safeName);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Path uploadDir = workspaceDir.resolve("uploads").resolve(id);
        Files.createDirectories(uploadDir);
        Path savedFile = uploadDir.resolve(safeName);
        Files.write(savedFile, bytes);
        String workspacePath = "uploads/" + id + "/" + safeName;

        if (IMAGE_MIMES.containsKey(ext)) {
            return processImage(
                    id, safeName, bytes, IMAGE_MIMES.get(ext), workspacePath, savedFile);
        }
        if ("zip".equals(ext)) {
            return processZip(id, safeName, bytes, uploadDir.resolve("extracted"));
        }
        if (TEXT_EXTENSIONS.contains(ext)
                || HTML_EXTENSIONS.contains(ext)
                || TIKA_EXTENSIONS.contains(ext)) {
            return processDocument(id, safeName, ext, savedFile, bytes, workspacePath);
        }
        if (looksLikeUtf8Text(bytes)) {
            return processDocument(id, safeName, "txt", savedFile, bytes, workspacePath);
        }
        throw new IllegalArgumentException(
                "不支持的附件格式，仅支持图片、ZIP、Office（Word/PPT/Excel）、PDF、Markdown 及常见代码/文本文件");
    }

    /**
     * Processes a local file or directory path (from clipboard/drag path URI or inline user text)
     * into the same attachment metadata and extracted text format as {@link #process}.
     */
    public static Map<String, Object> processLocalPath(String rawPathOrUri, Path workspaceDir)
            throws Exception {
        Path resolved = resolveLocalPath(rawPathOrUri, workspaceDir);
        if (resolved == null || !Files.exists(resolved)) {
            throw new IllegalArgumentException("找不到本地文件: " + rawPathOrUri);
        }
        if (Files.isDirectory(resolved)) {
            return processDirectory(resolved);
        }
        long size = Files.size(resolved);
        if (size <= 0) {
            throw new IllegalArgumentException("附件内容为空");
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("单个附件大小不能超过 20MB");
        }
        byte[] bytes = Files.readAllBytes(resolved);
        String fileName =
                resolved.getFileName() != null ? resolved.getFileName().toString() : "attachment";
        return processBytes(fileName, bytes, workspaceDir);
    }

    /**
     * Resolves a raw path string, {@code file://} URI, or {@code @path} token to an existing local
     * {@link Path}, or returns {@code null} if the string cannot be mapped to an existing path.
     */
    public static Path resolveLocalPath(String raw, Path workspaceDir) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.strip();
        while (candidate.length() >= 2
                && ((candidate.startsWith("\"") && candidate.endsWith("\""))
                        || (candidate.startsWith("'") && candidate.endsWith("'"))
                        || (candidate.startsWith("`") && candidate.endsWith("`"))
                        || (candidate.startsWith("“") && candidate.endsWith("”"))
                        || (candidate.startsWith("‘") && candidate.endsWith("’")))) {
            candidate = candidate.substring(1, candidate.length() - 1).strip();
        }
        if (candidate.startsWith("@")) {
            candidate = candidate.substring(1).strip();
        }
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            if (workspaceDir == null) {
                return null;
            }
            Path root = workspaceDir.toAbsolutePath().normalize();
            Path path = null;
            if (candidate.startsWith("file://")) {
                try {
                    path = Path.of(URI.create(candidate)).toAbsolutePath().normalize();
                } catch (Exception ignored) {
                    String stripped = candidate.substring("file://".length());
                    if (stripped.startsWith("localhost/")) {
                        stripped = stripped.substring("localhost".length());
                    }
                    String decoded = URLDecoder.decode(stripped, StandardCharsets.UTF_8);
                    path = Path.of(decoded).toAbsolutePath().normalize();
                }
            } else if (!candidate.startsWith("~") && !candidate.startsWith("/")) {
                path = root.resolve(candidate).normalize();
            } else {
                path = Path.of(candidate).normalize();
            }
            if (path != null && path.startsWith(root) && Files.exists(path)) {
                return path;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Scans a user message for local file or directory references (pasted/dragged paths, {@code
     * file://} URIs, or {@code @path} tokens) that were not uploaded as explicit attachment chips,
     * and appends their parsed content blocks so the model can directly read them.
     */
    public static String expandInlineFileReferences(String text, Path workspaceDir) {
        if (text == null || text.isBlank() || workspaceDir == null) {
            return text;
        }
        Set<Path> discovered = new LinkedHashSet<>();

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            Path wholeLine = resolveLocalPath(line, workspaceDir);
            if (wholeLine != null) {
                discovered.add(wholeLine);
                continue;
            }
            Matcher quoted = QUOTED_PATH_PATTERN.matcher(line);
            while (quoted.find()) {
                Path qPath = resolveLocalPath(quoted.group(1), workspaceDir);
                if (qPath != null) {
                    discovered.add(qPath);
                }
            }
            Matcher inline = INLINE_PATH_START_PATTERN.matcher(line);
            while (inline.find()) {
                Path matched =
                        matchExistingPathFromIndex(
                                line, inline.start(), inline.group(), workspaceDir);
                if (matched != null) {
                    discovered.add(matched);
                }
            }
        }

        if (discovered.isEmpty()) {
            return text;
        }

        StringBuilder appended = new StringBuilder();
        for (Path path : discovered) {
            String fileName =
                    path.getFileName() != null ? path.getFileName().toString() : path.toString();
            if (text.contains("[文档附件: " + fileName)
                    || text.contains("[ZIP 归档附件: " + fileName)
                    || text.contains("[图片附件: " + fileName)
                    || text.contains("[本地目录: " + fileName)) {
                continue;
            }
            try {
                Map<String, Object> parsed = processLocalPath(path.toString(), workspaceDir);
                Object extracted = parsed.get("extractedText");
                if (extracted instanceof String s && !s.isBlank()) {
                    if (appended.length() > 0) {
                        appended.append("\n\n");
                    }
                    appended.append(s.strip());
                }
            } catch (Exception ignored) {
                // Leave message untouched if a matched token is unreadable or unsupported.
            }
        }

        if (appended.length() == 0) {
            return text;
        }
        return text + "\n\n" + appended;
    }

    private static Path matchExistingPathFromIndex(
            String line, int startIndex, String initialToken, Path workspaceDir) {
        String remainder = line.substring(startIndex).strip();
        // Try progressively shorter prefixes of remainder split on whitespace or CJK punctuation so
        // paths containing spaces (e.g. "/Users/a/My File.pdf 请分析") still match the file on disk.
        List<Integer> cutPoints = new ArrayList<>();
        cutPoints.add(remainder.length());
        for (int i = remainder.length() - 1; i > 0; i--) {
            char c = remainder.charAt(i);
            if (Character.isWhitespace(c) || "，。；！？,;!?)】》>\"'`”’".indexOf(c) >= 0) {
                cutPoints.add(i);
            }
        }
        for (int end : cutPoints) {
            String sub = stripTrailingPunctuation(remainder.substring(0, end));
            if (sub.length() < 2) {
                continue;
            }
            Path candidate = resolveLocalPath(sub, workspaceDir);
            if (candidate != null) {
                return candidate;
            }
        }
        return resolveLocalPath(stripTrailingPunctuation(initialToken), workspaceDir);
    }

    private static String stripTrailingPunctuation(String token) {
        int end = token.length();
        while (end > 0 && "，。；！？,;!?)】》>\"'`”’.".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end).strip();
    }

    private static Map<String, Object> processDirectory(Path dir) throws IOException {
        Path normalizedRoot = dir.toAbsolutePath().normalize();
        String dirName =
                normalizedRoot.getFileName() != null
                        ? normalizedRoot.getFileName().toString()
                        : normalizedRoot.toString();
        List<String> entries = new ArrayList<>();
        StringBuilder snippets = new StringBuilder();
        try (Stream<Path> walk = Files.walk(normalizedRoot, 4)) {
            for (Path item : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(item)) {
                    continue;
                }
                String rel = normalizedRoot.relativize(item).toString().replace('\\', '/');
                if (isIgnoredArchiveEntry(rel)) {
                    continue;
                }
                if (entries.size() >= MAX_ZIP_ENTRIES) {
                    break;
                }
                entries.add(rel);
                String ext = extension(rel);
                long size = Files.size(item);
                if (size > 0
                        && size <= 512 * 1024
                        && snippets.length() < MAX_EXTRACTED_CHARS
                        && TEXT_EXTENSIONS.contains(ext)) {
                    byte[] entryBytes = Files.readAllBytes(item);
                    String content = new String(entryBytes, StandardCharsets.UTF_8).strip();
                    if (!content.isEmpty()) {
                        if (content.length() > MAX_ZIP_SNIPPET_CHARS) {
                            content =
                                    content.substring(0, MAX_ZIP_SNIPPET_CHARS)
                                            + "\n...(已截断，完整文件位于 "
                                            + item.toAbsolutePath()
                                            + ")";
                        }
                        snippets.append("\n--- ")
                                .append(rel)
                                .append(" ---\n")
                                .append(content)
                                .append("\n");
                    }
                }
            }
        }
        String workspacePath = normalizedRoot.toString();
        StringBuilder summary = new StringBuilder();
        summary.append("[本地目录: ")
                .append(dirName)
                .append("，路径: ")
                .append(workspacePath)
                .append("，共 ")
                .append(entries.size())
                .append(" 个文件]\n文件列表:\n");
        for (String item : entries) {
            summary.append("- ").append(item).append("\n");
        }
        if (snippets.length() > 0) {
            summary.append("\n关键文件内容摘要:").append(snippets);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        out.put("name", dirName);
        out.put("size", 0);
        out.put("kind", "archive");
        out.put("mimeType", "inode/directory");
        out.put("workspacePath", workspacePath);
        out.put("extractedText", truncateText(summary.toString(), workspacePath));
        return out;
    }

    private static final Pattern WORKSPACE_UPLOAD_PATH_PATTERN =
            Pattern.compile("(uploads/[a-zA-Z0-9_-]+/[^\\]\\s，,]+)");

    /**
     * Extracts native multimodal {@link ImageBlock}s for any workspace image attachments referenced
     * in a user message. For ultra-long screenshots that were sliced during upload, returns the
     * normal-aspect-ratio visual slices so vision models do not crush the image resolution.
     */
    public static List<ImageBlock> extractImageBlocksForMessage(String text, Path workspaceDir) {
        if (text == null || text.isBlank() || workspaceDir == null) {
            return List.of();
        }
        List<ImageBlock> blocks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = WORKSPACE_UPLOAD_PATH_PATTERN.matcher(text);
        Path normalizedRoot = workspaceDir.toAbsolutePath().normalize();
        while (matcher.find()) {
            String relPath = matcher.group(1);
            if (!seen.add(relPath)) {
                continue;
            }
            Path resolved = normalizedRoot.resolve(relPath).normalize();
            if (!resolved.startsWith(normalizedRoot) || !Files.isRegularFile(resolved)) {
                continue;
            }
            String ext = extension(resolved.getFileName().toString());
            String mimeType = IMAGE_MIMES.get(ext);
            if (mimeType == null) {
                continue;
            }
            try {
                Path slicesDir = resolved.getParent().resolve("slices");
                if (Files.isDirectory(slicesDir)) {
                    try (Stream<Path> sliceFiles = Files.list(slicesDir)) {
                        List<Path> sorted =
                                sliceFiles.filter(Files::isRegularFile).sorted().limit(6).toList();
                        if (!sorted.isEmpty()) {
                            for (Path slice : sorted) {
                                byte[] sliceBytes = Files.readAllBytes(slice);
                                blocks.add(
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(
                                                                        Base64.getEncoder()
                                                                                .encodeToString(
                                                                                        sliceBytes))
                                                                .build())
                                                .build());
                            }
                            continue;
                        }
                    }
                }
                byte[] bytes = Files.readAllBytes(resolved);
                blocks.add(
                        ImageBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .mediaType(mimeType)
                                                .data(Base64.getEncoder().encodeToString(bytes))
                                                .build())
                                .build());
            } catch (Exception ignored) {
                // Skip unreadable image file.
            }
        }
        return blocks;
    }

    private static Map<String, Object> processImage(
            String id,
            String name,
            byte[] bytes,
            String mimeType,
            String workspacePath,
            Path savedFile) {
        String dimensions = "";
        int width = 0;
        int height = 0;
        BufferedImage img = null;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
                dimensions = width + "x" + height;
            }
        } catch (Exception ignored) {
            // Keep empty dimensions if the image reader does not support the format (e.g. some
            // webp).
        }
        List<Path> sliceFiles = List.of();
        if (img != null && savedFile != null && width >= 16 && height >= 16) {
            sliceFiles = saveLongImageSlicesIfNeeded(img, savedFile.getParent().resolve("slices"));
        }
        String ocrText = "";
        if (savedFile != null && width >= 16 && height >= 16) {
            ocrText = extractImageTextOcr(savedFile, sliceFiles);
        }
        String sidecarPath = null;
        if (savedFile != null && !ocrText.isEmpty()) {
            try {
                Path sidecar = savedFile.resolveSibling(name + ".extracted.txt");
                Files.writeString(sidecar, ocrText, StandardCharsets.UTF_8);
                sidecarPath = workspacePath + ".extracted.txt";
            } catch (Exception ignored) {
                // Best-effort sidecar creation.
            }
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;
        String note =
                "[图片附件: "
                        + name
                        + (dimensions.isEmpty() ? "" : " (" + dimensions + ")")
                        + "，工作区路径: "
                        + workspacePath
                        + (sidecarPath != null ? "，提取文本文件: " + sidecarPath : "")
                        + "]"
                        + (ocrText.isEmpty()
                                ? ""
                                : "\n图片识别文字内容:\n"
                                        + truncateText(
                                                ocrText,
                                                sidecarPath != null ? sidecarPath : workspacePath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("size", bytes.length);
        out.put("kind", "image");
        out.put("mimeType", mimeType);
        out.put("dataUrl", dataUrl);
        out.put("workspacePath", workspacePath);
        out.put("extractedText", note);
        return out;
    }

    /**
     * When an image has an extreme vertical or horizontal aspect ratio (e.g. a 1080x13207 long
     * screenshot), slices it with overlapping windows into normal-aspect-ratio tiles so both OCR
     * engines and vision models preserve full text legibility instead of downscaling the longest
     * edge by 10x.
     */
    private static List<Path> saveLongImageSlicesIfNeeded(BufferedImage img, Path slicesDir) {
        int w = img.getWidth();
        int h = img.getHeight();
        int tileHeight = Math.max(w * 2, 1800);
        int overlap = 120;
        if (h <= tileHeight + overlap) {
            return List.of();
        }
        List<Path> savedSlices = new ArrayList<>();
        try {
            Files.createDirectories(slicesDir);
            int idx = 0;
            int y = 0;
            while (y < h && idx < 20) {
                int sliceH = Math.min(tileHeight, h - y);
                BufferedImage sub = img.getSubimage(0, y, w, sliceH);
                Path sliceFile = slicesDir.resolve(String.format("slice_%02d.png", idx));
                ImageIO.write(sub, "png", sliceFile.toFile());
                savedSlices.add(sliceFile);
                idx++;
                if (y + sliceH >= h) {
                    break;
                }
                y += (tileHeight - overlap);
            }
        } catch (Exception ignored) {
            // Return whatever slices succeeded.
        }
        return savedSlices;
    }

    private static synchronized Path ensureVisionOcrBinary() {
        Path bin = Path.of(System.getProperty("java.io.tmpdir"), "advisor-vision-ocr-v2");
        if (Files.isExecutable(bin)) {
            return bin;
        }
        Path swiftc = Path.of("/usr/bin/swiftc");
        if (!Files.isExecutable(swiftc)) {
            return null;
        }
        try {
            Path src = Path.of(System.getProperty("java.io.tmpdir"), "advisor-vision-ocr-v2.swift");
            Files.writeString(
                    src,
                    """
                    import Foundation
                    import Vision
                    import AppKit
                    guard CommandLine.arguments.count > 1 else { exit(0) }
                    let url = URL(fileURLWithPath: CommandLine.arguments[1])
                    guard let img = NSImage(contentsOf: url),
                          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { exit(0) }

                    func recognize(_ image: CGImage) -> [String] {
                        let req = VNRecognizeTextRequest()
                        req.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
                        req.recognitionLevel = .accurate
                        req.usesLanguageCorrection = true
                        try? VNImageRequestHandler(cgImage: image, options: [:]).perform([req])
                        var lines: [String] = []
                        for obs in req.results ?? [] {
                            if let top = obs.topCandidates(1).first { lines.append(top.string) }
                        }
                        return lines
                    }

                    let w = cg.width
                    let h = cg.height
                    let tileHeight = max(w * 2, 1800)
                    let overlap = 120
                    if h > tileHeight + overlap {
                        var y = 0
                        var prevTail: Set<String> = []
                        while y < h {
                            let sliceH = min(tileHeight, h - y)
                            let rect = CGRect(x: 0, y: y, width: w, height: sliceH)
                            if let cropped = cg.cropping(to: rect) {
                                let lines = recognize(cropped)
                                for (idx, line) in lines.enumerated() {
                                    if idx < 4 && prevTail.contains(line) { continue }
                                    print(line)
                                }
                                prevTail = Set(lines.suffix(6))
                            }
                            if y + sliceH >= h { break }
                            y += (tileHeight - overlap)
                        }
                    } else {
                        for line in recognize(cg) {
                            print(line)
                        }
                    }
                    """,
                    StandardCharsets.UTF_8);
            Process compile =
                    new ProcessBuilder(
                                    swiftc.toString(), "-O", src.toString(), "-o", bin.toString())
                            .redirectErrorStream(true)
                            .start();
            if (compile.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                    && compile.exitValue() == 0
                    && Files.isExecutable(bin)) {
                return bin;
            }
        } catch (Exception ignored) {
            // Fall back to tesseract or empty OCR below.
        }
        return null;
    }

    private static String extractImageTextOcr(Path imageFile, List<Path> sliceFiles) {
        try {
            Path visionBin = ensureVisionOcrBinary();
            if (visionBin != null) {
                Process proc =
                        new ProcessBuilder(
                                        visionBin.toString(), imageFile.toAbsolutePath().toString())
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                byte[] out = proc.getInputStream().readAllBytes();
                if (proc.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
                        && proc.exitValue() == 0) {
                    String text = new String(out, StandardCharsets.UTF_8).strip();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
            List<Path> targets =
                    (sliceFiles != null && !sliceFiles.isEmpty()) ? sliceFiles : List.of(imageFile);
            for (String tesseract :
                    List.of(
                            "/opt/homebrew/bin/tesseract",
                            "/usr/local/bin/tesseract",
                            "tesseract")) {
                try {
                    StringBuilder combined = new StringBuilder();
                    for (Path target : targets) {
                        Process proc =
                                new ProcessBuilder(
                                                tesseract,
                                                target.toAbsolutePath().toString(),
                                                "stdout",
                                                "-l",
                                                "chi_sim+eng")
                                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                                        .start();
                        byte[] out = proc.getInputStream().readAllBytes();
                        if (proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                                && proc.exitValue() == 0) {
                            String part = new String(out, StandardCharsets.UTF_8).strip();
                            if (!part.isEmpty()) {
                                if (combined.length() > 0) combined.append('\n');
                                combined.append(part);
                            }
                        }
                    }
                    String text = combined.toString().strip();
                    if (!text.isEmpty()) {
                        return text;
                    }
                } catch (Exception ignored) {
                    // Try next candidate.
                }
            }
        } catch (Exception ignored) {
            // OCR is best-effort.
        }
        return "";
    }

    private static Map<String, Object> processZip(
            String id, String name, byte[] bytes, Path extractRoot) throws IOException {
        Files.createDirectories(extractRoot);
        Path normalizedRoot = extractRoot.normalize();
        List<String> entries = new ArrayList<>();
        StringBuilder snippets = new StringBuilder();
        long totalExtracted = 0;

        try (ZipInputStream zis =
                new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                if (isIgnoredArchiveEntry(entryName)) {
                    continue;
                }
                if (entries.size() >= MAX_ZIP_ENTRIES) {
                    break;
                }
                Path target = normalizedRoot.resolve(entryName).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IllegalArgumentException("ZIP 压缩包包含非法路径");
                }
                byte[] entryBytes = readBoundedEntry(zis, MAX_ZIP_TOTAL_BYTES - totalExtracted);
                totalExtracted += entryBytes.length;
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.write(target, entryBytes);
                entries.add(entryName);

                String entryExt = extension(entryName);
                if (snippets.length() < MAX_EXTRACTED_CHARS
                        && (TEXT_EXTENSIONS.contains(entryExt) || looksLikeUtf8Text(entryBytes))) {
                    String content = new String(entryBytes, StandardCharsets.UTF_8).strip();
                    if (!content.isEmpty()) {
                        if (content.length() > MAX_ZIP_SNIPPET_CHARS) {
                            content =
                                    content.substring(0, MAX_ZIP_SNIPPET_CHARS)
                                            + "\n...(已截断，完整文件位于 uploads/"
                                            + id
                                            + "/extracted/"
                                            + entryName
                                            + ")";
                        }
                        snippets.append("\n--- ")
                                .append(entryName)
                                .append(" ---\n")
                                .append(content)
                                .append("\n");
                    }
                }
                if (totalExtracted >= MAX_ZIP_TOTAL_BYTES) {
                    break;
                }
            }
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("ZIP 压缩包中没有可读取的文件");
        }

        String workspacePath = "uploads/" + id + "/extracted";
        StringBuilder summary = new StringBuilder();
        summary.append("[ZIP 归档附件: ")
                .append(name)
                .append("，已解压至工作区: ")
                .append(workspacePath)
                .append("，共 ")
                .append(entries.size())
                .append(" 个文件]\n文件列表:\n");
        for (String item : entries) {
            summary.append("- ").append(item).append("\n");
        }
        if (snippets.length() > 0) {
            summary.append("\n关键文件内容摘要:").append(snippets);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("size", bytes.length);
        out.put("kind", "archive");
        out.put("mimeType", "application/zip");
        out.put("workspacePath", workspacePath);
        out.put("extractedText", truncateText(summary.toString(), workspacePath));
        return out;
    }

    private static Map<String, Object> processDocument(
            String id, String name, String ext, Path savedFile, byte[] bytes, String workspacePath)
            throws Exception {
        String text = extractDocumentText(ext, savedFile, bytes, name).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("没有从附件中读出可用文本内容");
        }
        String readableWorkspacePath = workspacePath;
        if (TIKA_EXTENSIONS.contains(ext)) {
            Path extractedFile = savedFile.resolveSibling(name + ".extracted.txt");
            Files.writeString(extractedFile, text, StandardCharsets.UTF_8);
            readableWorkspacePath = workspacePath + ".extracted.txt";
        }
        String formatted =
                "[文档附件: "
                        + name
                        + "，工作区路径: "
                        + readableWorkspacePath
                        + "]\n"
                        + truncateText(text, readableWorkspacePath);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("size", bytes.length);
        out.put("kind", "document");
        out.put("mimeType", documentMimeType(ext));
        out.put("workspacePath", readableWorkspacePath);
        out.put("extractedText", formatted);
        return out;
    }

    private static String extractDocumentText(
            String ext, Path savedFile, byte[] bytes, String filename) throws Exception {
        if (TEXT_EXTENSIONS.contains(ext)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (HTML_EXTENSIONS.contains(ext)) {
            return stripHtml(new String(bytes, StandardCharsets.UTF_8));
        }
        if ("pdf".equals(ext)) {
            try {
                List<Document> docs =
                        new PDFReader(4000, SplitStrategy.PARAGRAPH, 0)
                                .read(ReaderInput.fromFile(savedFile))
                                .block();
                String joined = joinDocuments(docs);
                if (!joined.isBlank()) {
                    return joined;
                }
            } catch (Exception ignored) {
                // Fall back to Tika below.
            }
        }
        if ("docx".equals(ext)) {
            try {
                List<Document> docs =
                        new WordReader(
                                        4000,
                                        SplitStrategy.PARAGRAPH,
                                        0,
                                        false,
                                        true,
                                        TableFormat.MARKDOWN)
                                .read(ReaderInput.fromFile(savedFile))
                                .block();
                String joined = joinDocuments(docs);
                if (!joined.isBlank()) {
                    return joined;
                }
            } catch (Exception ignored) {
                // Fall back to Tika below.
            }
        }
        return parseWithTika(savedFile, filename);
    }

    private static String parseWithTika(Path file, String filename) throws Exception {
        try (InputStream stream = Files.newInputStream(file)) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            parser.parse(stream, handler, metadata, new ParseContext());
            return handler.toString();
        }
    }

    private static String joinDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Document doc : docs) {
            String part = doc.getMetadata().getContentText();
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(part.strip());
            }
        }
        return sb.toString();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ");
    }

    private static String truncateText(String text, String workspacePath) {
        if (text.length() <= MAX_EXTRACTED_CHARS) {
            return text;
        }
        return text.substring(0, MAX_EXTRACTED_CHARS)
                + "\n...(已截断，完整内容位于工作区 "
                + workspacePath
                + ")";
    }

    private static boolean isIgnoredArchiveEntry(String name) {
        if (name.isBlank() || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) {
            return true;
        }
        for (String segment : name.split("/")) {
            if (".git".equals(segment)
                    || "node_modules".equals(segment)
                    || "__pycache__".equals(segment)
                    || "target".equals(segment)
                    || "dist".equals(segment)
                    || ".idea".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readBoundedEntry(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("ZIP 解压后大小超过上限");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean looksLikeUtf8Text(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return "dockerfile".equals(lower) ? "dockerfile" : "";
        }
        return lower.substring(dot + 1);
    }

    private static String documentMimeType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv" -> "text/csv";
            case "md", "markdown" -> "text/markdown";
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            default -> "text/plain";
        };
    }
}
