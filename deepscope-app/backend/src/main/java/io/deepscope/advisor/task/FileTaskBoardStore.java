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
package io.deepscope.advisor.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地工作区文件存储模式（模式 A）：
 * 存储在本地 JSON 文件中，支持原子写入（Write-Replace）与启动现场重建。
 */
public class FileTaskBoardStore implements TaskBoardStore {

    private static final Logger log = LoggerFactory.getLogger(FileTaskBoardStore.class);

    private final Path storageDir;
    private final ObjectMapper objectMapper;

    public FileTaskBoardStore(Path storageDir, ObjectMapper objectMapper) {
        this.storageDir = storageDir;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public FileTaskBoardStore(Path storageDir) {
        this(storageDir, new ObjectMapper());
    }

    @Override
    public Optional<TaskBoard> loadBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Path filePath = resolveBoardFile(sessionId);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }

        try {
            TaskBoardSnapshot snapshot =
                    objectMapper.readValue(filePath.toFile(), TaskBoardSnapshot.class);
            return Optional.of(TaskBoard.fromSnapshot(snapshot));
        } catch (Exception e) {
            log.warn("Failed to load task board from file: {}", filePath, e);
            return Optional.empty();
        }
    }

    @Override
    public void saveBoard(TaskBoard board) {
        if (board == null) {
            return;
        }
        Path targetFile = resolveBoardFile(board.getSessionId());
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp");

        try {
            Files.createDirectories(targetFile.getParent());
            TaskBoardSnapshot snapshot = board.toSnapshot();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), snapshot);

            try {
                Files.move(
                        tempFile,
                        targetFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to save task board atomically to file: {}", targetFile, e);
            throw new RuntimeException("Failed to persist task board: " + board.getSessionId(), e);
        }
    }

    @Override
    public void deleteBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path filePath = resolveBoardFile(sessionId);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete task board file: {}", filePath, e);
        }
    }

    private Path resolveBoardFile(String sessionId) {
        return storageDir.resolve("sessions").resolve(sessionId).resolve("task-board.json");
    }
}
