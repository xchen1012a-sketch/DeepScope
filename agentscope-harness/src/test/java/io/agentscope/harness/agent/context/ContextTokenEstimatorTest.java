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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The estimator must not undercount CJK text: one CJK char is roughly one token, not 0.4. */
class ContextTokenEstimatorTest {

    @Test
    void cjkTextCountsRoughlyOneTokenPerCharacter() {
        // 100 个汉字：按旧公式（长度/2.5）只估 52，现代分词器实际约 100+，必须按 1:1 计。
        long tokens = ContextTokenEstimator.approximate().estimate(input("字".repeat(100))).tokens();
        assertTrue(tokens >= 100, "100 CJK chars must estimate to >=100 tokens, got " + tokens);
    }

    @Test
    void latinTextKeepsTheFourCharsPerTokenRatio() {
        // 100 个拉丁字符：4 字符/token → 约 25，加消息固定开销 12，远离 1:1 的 CJK 口径。
        long tokens = ContextTokenEstimator.approximate().estimate(input("a".repeat(100))).tokens();
        assertTrue(tokens < 50, "100 latin chars must stay near 4 chars/token, got " + tokens);
    }

    private static ModelCallInput input(String text) {
        return new ModelCallInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()),
                List.of(),
                null,
                null);
    }
}
