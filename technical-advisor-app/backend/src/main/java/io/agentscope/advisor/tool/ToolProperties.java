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
package io.agentscope.advisor.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tool catalog settings.
 *
 * <p>The credential key is the deployment's, not the app's: without it a tool that needs
 * credentials cannot be saved, while tools that need none keep working.
 */
@ConfigurationProperties(prefix = "advisor.tools")
public record ToolProperties(@DefaultValue("") String credentialKey) {
    @Override
    public String toString() {
        return "ToolProperties[credentialKey=" + (credentialKey.isBlank() ? "unset" : "set") + "]";
    }
}
