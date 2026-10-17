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
package io.deepscope.advisor.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.middleware.AgentInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class SessionAttachmentsTest {

    @TempDir Path tempDir;

    @Test
    void processesLocalFileAndFileUriWithSpacesAndChineseCharacters() throws Exception {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);

        Path sourceFile = workspaceDir.resolve("架构设计 规范 v1.md");
        Files.writeString(sourceFile, "# 核心架构\n采用分层事件流设计。", StandardCharsets.UTF_8);

        String fileUri = sourceFile.toUri().toString();
        Map<String, Object> parsed = SessionAttachments.processLocalPath(fileUri, workspaceDir);

        assertThat(parsed.get("kind")).isEqualTo("document");
        assertThat(parsed.get("name")).isEqualTo("架构设计 规范 v1.md");
        assertThat(String.valueOf(parsed.get("extractedText")))
                .contains("[文档附件: 架构设计 规范 v1.md")
                .contains("采用分层事件流设计。");
    }

    @Test
    void rejectsExternalHostPathOutsideWorkspace() throws Exception {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);

        Path externalFile = tempDir.resolve("external.txt");
        Files.writeString(externalFile, "sensitive", StandardCharsets.UTF_8);

        assertThat(SessionAttachments.resolveLocalPath(externalFile.toString(), workspaceDir))
                .isNull();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SessionAttachments.processLocalPath(externalFile.toString(), workspaceDir));
    }

    @Test
    void expandsInlineFilePathsAndDirectoriesInUserInput() throws Exception {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);

        Path notesFile = workspaceDir.resolve("接口说明.txt");
        Files.writeString(notesFile, "接口鉴权使用 Bearer Token。", StandardCharsets.UTF_8);

        String userPrompt = "请帮我阅读 " + notesFile + " 总结鉴权方式";
        String expanded = SessionAttachments.expandInlineFileReferences(userPrompt, workspaceDir);

        assertThat(expanded)
                .startsWith(userPrompt)
                .contains("[文档附件: 接口说明.txt")
                .contains("接口鉴权使用 Bearer Token。");
    }

    @Test
    void middlewareEnrichesUserMessageWithInlineFileContents() throws Exception {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);

        Path codeFile = workspaceDir.resolve("Demo.java");
        Files.writeString(codeFile, "public class Demo { int x = 42; }", StandardCharsets.UTF_8);

        InlineFileReferenceMiddleware middleware = new InlineFileReferenceMiddleware(workspaceDir);
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .textContent(codeFile.toString() + "\n解释这段代码")
                        .build();

        AtomicReference<AgentInput> captured = new AtomicReference<>();
        middleware
                .onAgent(
                        null,
                        RuntimeContext.empty(),
                        new AgentInput(List.of(userMsg)),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertThat(captured.get()).isNotNull();
        String finalContent = captured.get().msgs().get(0).getTextContent();
        assertThat(finalContent)
                .contains("[文档附件: Demo.java")
                .contains("public class Demo { int x = 42; }");
    }
}
