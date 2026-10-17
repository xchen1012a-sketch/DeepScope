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

import java.util.Optional;

/**
 * 任务看板持久化存储抽象接口（对标工业级双模持久化规范）。
 */
public interface TaskBoardStore {

    /**
     * 根据会话 ID 加载并现场重建任务看板。
     */
    Optional<TaskBoard> loadBoard(String sessionId);

    /**
     * 保存任务看板快照。
     */
    void saveBoard(TaskBoard board);

    /**
     * 删除并清理会话看板数据。
     */
    void deleteBoard(String sessionId);
}
