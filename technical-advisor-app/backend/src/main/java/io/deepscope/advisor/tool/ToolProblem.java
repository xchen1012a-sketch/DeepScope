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
package io.deepscope.advisor.tool;

import java.util.Map;

/** Stable module errors without exposing connection credentials or remote exception text. */
public final class ToolProblem extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ToolProblem(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ToolProblem(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> body() {
        var body = new java.util.LinkedHashMap<String, Object>(details);
        body.put("code", code);
        body.put("messageKey", "tool.errors." + code);
        body.put("message", getMessage());
        return body;
    }
}
