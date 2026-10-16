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
package io.agentscope.advisor.model;

import io.agentscope.core.model.Model;
import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chat port: the current saved endpoint, otherwise empty so the local model is used. */
public final class SavedChatModelSource implements ChatModelSource {

    private static final Logger log = LoggerFactory.getLogger(SavedChatModelSource.class);

    private final ModelCatalog catalog;
    private final OpenAiEndpointClient client;
    private final io.agentscope.advisor.session.SessionStore sessions;

    public SavedChatModelSource(ModelCatalog catalog, OpenAiEndpointClient client) {
        this(catalog, client, null);
    }

    public SavedChatModelSource(
            ModelCatalog catalog,
            OpenAiEndpointClient client,
            io.agentscope.advisor.session.SessionStore sessions) {
        this.sessions = sessions;
        this.catalog = catalog;
        this.client = client;
    }

    @Override
    public Optional<Model> forSession(String sessionId) {
        if (sessions == null || sessionId == null) return current();
        try {
            String endpointId =
                    sessions.find(sessionId)
                            .map(
                                    io.agentscope.advisor.session.SessionStore.SessionRow
                                            ::modelEndpointId)
                            .orElse(null);
            return forEndpoint(endpointId);
        } catch (SQLException error) {
            throw new IllegalStateException("Session model could not be read", error);
        }
    }

    @Override
    public Optional<Model> forEndpoint(String endpointId) {
        if (endpointId == null) return current();
        try {
            var endpoint =
                    catalog.find(endpointId)
                            .filter(value -> ModelCatalog.CHAT.equals(value.kind()))
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Selected model is unavailable"));
            return Optional.of(
                    client.chat(endpoint, catalog.contextWindow(endpointId).orElse(null)));
        } catch (SQLException error) {
            throw new IllegalStateException("Selected model is unavailable", error);
        }
    }

    @Override
    public Optional<Model> current() {
        try {
            var endpoint = catalog.current(ModelCatalog.CHAT);
            if (endpoint.isEmpty()) {
                return Optional.empty();
            }
            var selected = endpoint.get();
            return Optional.of(
                    client.chat(
                            selected, catalog.contextWindow(selected.endpointId()).orElse(null)));
        } catch (SQLException error) {
            log.warn("Current chat endpoint could not be read: {}", error.getMessage());
            return Optional.empty();
        }
    }
}
