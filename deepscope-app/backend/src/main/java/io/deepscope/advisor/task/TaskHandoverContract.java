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

import java.util.ArrayList;
import java.util.List;

/**
 * 子智能体向主 Agent 与看板提交的精炼交接契约（严格限制在核心三要素）。
 *
 * @param conclusion 核心发现与产出结论（3 句话以内，不超过 200 字）
 * @param artifacts 产出物清单（生成的文件路径、数据编号或关键标识）
 * @param risks 遗留风险、阻塞点或下游注意事项
 */
public record TaskHandoverContract(String conclusion, List<String> artifacts, String risks) {

    public TaskHandoverContract {
        conclusion = conclusion == null ? "" : conclusion.strip();
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        risks = risks == null ? "" : risks.strip();
    }

    /**
     * 格式化为符合看板规范的精炼交接文本。
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        if (!conclusion.isBlank()) {
            sb.append("【结论】").append(conclusion);
        }
        if (!artifacts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("【产出物】").append(String.join(", ", artifacts));
        }
        if (!risks.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("【风险提示】").append(risks);
        }
        return sb.toString();
    }

    /**
     * 从自由文本或格式化文本解析出交付契约。
     */
    public static TaskHandoverContract parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new TaskHandoverContract("", List.of(), "");
        }

        String conclusion = "";
        List<String> artifacts = new ArrayList<>();
        String risks = "";

        if (rawText.contains("【结论】") || rawText.contains("【产出物】") || rawText.contains("【风险提示】")) {
            String[] segments = rawText.split("\\s*\\|\\s*");
            for (String segment : segments) {
                if (segment.startsWith("【结论】")) {
                    conclusion = segment.substring("【结论】".length()).strip();
                } else if (segment.startsWith("【产出物】")) {
                    String arts = segment.substring("【产出物】".length()).strip();
                    for (String art : arts.split("[,，]")) {
                        if (!art.isBlank()) {
                            artifacts.add(art.strip());
                        }
                    }
                } else if (segment.startsWith("【风险提示】")) {
                    risks = segment.substring("【风险提示】".length()).strip();
                }
            }
        } else {
            conclusion = rawText.strip();
        }

        return new TaskHandoverContract(conclusion, artifacts, risks);
    }
}
