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

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Advisor app runtime locations.
 *
 * <p>Everything the app writes on disk (seeded workspace, agent session state) stays under
 * {@code advisor.data-dir} so the whole demo remains contained in one deletable directory.
 */
@ConfigurationProperties(prefix = "advisor")
public record AdvisorProperties(
        /** Root directory for app runtime data (seeded workspace, session state). */
        @DefaultValue("technical-advisor-app/data") Path dataDir) {

    /** Directory the workspace template is seeded to and the agent evolves afterwards. */
    public Path workspaceDir() {
        return dataDir.resolve("workspace");
    }

    /** Root directory for persisted agent session state. */
    public Path stateDir() {
        return dataDir.resolve("state");
    }

    /** Root directory hosting every personal agent's workspace and state directories. */
    public Path agentsRoot() {
        return dataDir.resolve("agents");
    }

    /** Original knowledge files, kept so a library can be embedded again without a new upload. */
    public Path knowledgeDir() {
        return dataDir.resolve("knowledge");
    }
}
