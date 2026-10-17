/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.knowledge;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public configuration summary guarded by the existing knowledge view interceptor. */
@RestController
public final class KnowledgeModelController {
    private final PdfModelReader model;

    public KnowledgeModelController(PdfModelReader model) {
        this.model = model;
    }

    @GetMapping("/api/advisor/knowledge/pdf-model")
    public Map<String, Object> configuration() {
        return model.configuration();
    }
}
