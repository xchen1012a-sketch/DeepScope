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
package io.deepscope.advisor.model;

import io.deepscope.core.model.Model;
import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chat port: the current saved endpoint, otherwise empty so the local model is used. */
public final class SavedChatModelSource implements ChatModelSource {

    private static final Logger log = LoggerFactory.getLogger(SavedChatModelSource.class);

    private final ModelCatalog catalog;
    private final OpenAiEndpointClient client;
    private final io.deepscope.advisor.session.SessionStore sessions;

    public SavedChatModelSource(ModelCatalog catalog, OpenAiEndpointClient client) {
        this(catalog, client, null);
    }

    public SavedChatModelSource(
            ModelCatalog catalog,
            OpenAiEndpointClient client,
            io.deepscope.advisor.session.SessionStore sessions) {
        this.sessions = sessions;
        this.catalog = catalog;
        this.client = client;
    }

    @Override
    public Optional<Model> forSession(String sessionId) {
        if (sessions == null || sessionId == null) return current();
        try {
            var sessionOpt = sessions.find(sessionId);
            if (sessionOpt.isEmpty()) return current();
            var session = sessionOpt.get();
            String endpointId = session.modelEndpointId();
            if (endpointId != null) {
                var endpoint =
                        catalog.find(endpointId)
                                .filter(value -> ModelCatalog.CHAT.equals(value.kind()))
                                .orElse(null);
                if (endpoint != null) {
                    if (endpoint.isPublic()
                            || endpoint.ownerUserId() == null
                            || (endpoint.ownerUserId() != null
                                    && endpoint.ownerUserId().equals(session.ownerUserId()))) {
                        return Optional.of(
                                client.chat(
                                        endpoint, catalog.contextWindow(endpointId).orElse(null)));
                    }
                    log.warn(
                            "Session {} model {} is private to user {} and cannot be accessed by"
                                    + " session owner {}",
                            sessionId,
                            endpointId,
                            endpoint.ownerUserId(),
                            session.ownerUserId());
                }
            }
            var userDefault = catalog.current(ModelCatalog.CHAT, session.ownerUserId(), false);
            if (userDefault.isPresent()) {
                var endpoint = userDefault.get();
                return Optional.of(
                        client.chat(
                                endpoint,
                                catalog.contextWindow(endpoint.endpointId()).orElse(null)));
            }
            return Optional.empty();
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
