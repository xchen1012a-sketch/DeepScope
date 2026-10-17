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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskHandoverContractTest {

    @Test
    void formatAndParseStructuredHandover() {
        TaskHandoverContract contract =
                new TaskHandoverContract(
                        "已完成架构选型，确认采用双模持久化方案",
                        List.of("docs/architecture.md", "src/TaskBoard.java"),
                        "需要注意 H2 内存模式的 DB_CLOSE_DELAY 参数");

        String formatted = contract.format();
        assertTrue(formatted.contains("【结论】已完成架构选型，确认采用双模持久化方案"));
        assertTrue(formatted.contains("【产出物】docs/architecture.md, src/TaskBoard.java"));
        assertTrue(formatted.contains("【风险提示】需要注意 H2 内存模式的 DB_CLOSE_DELAY 参数"));

        TaskHandoverContract parsed = TaskHandoverContract.parse(formatted);
        assertEquals("已完成架构选型，确认采用双模持久化方案", parsed.conclusion());
        assertEquals(2, parsed.artifacts().size());
        assertEquals("docs/architecture.md", parsed.artifacts().get(0));
        assertEquals("src/TaskBoard.java", parsed.artifacts().get(1));
        assertEquals("需要注意 H2 内存模式的 DB_CLOSE_DELAY 参数", parsed.risks());
    }

    @Test
    void parseUnstructuredPlainTextGracefully() {
        String plain = "代码已修改并提交，全量测试已通过";
        TaskHandoverContract parsed = TaskHandoverContract.parse(plain);
        assertEquals(plain, parsed.conclusion());
        assertTrue(parsed.artifacts().isEmpty());
        assertTrue(parsed.risks().isEmpty());
    }
}
