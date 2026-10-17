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
package io.deepscope.advisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Technical Advisor demo app entry point.
 *
 * <p>Assembly only: the agent's behavior lives in the workspace files under
 * {@code src/main/resources/workspace}; the framework starters contribute the chat surface
 * (AG-UI streaming endpoints), the ops surface (agent inventory / session admin) and the model
 * bean (DashScope). All runtime data stays under {@code advisor.data-dir}.
 */
@SpringBootApplication
@EnableConfigurationProperties({
    AdvisorProperties.class,
    io.deepscope.advisor.knowledge.EmbeddingProperties.class,
    io.deepscope.advisor.tool.ToolProperties.class,
    io.deepscope.advisor.task.TaskGovernorProperties.class
})
public class TechnicalAdvisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechnicalAdvisorApplication.class, args);
    }
}
