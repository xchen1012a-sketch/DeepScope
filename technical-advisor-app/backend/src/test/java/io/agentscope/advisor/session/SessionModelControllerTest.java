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
package io.agentscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.advisor.access.AccessCatalog;
import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.advisor.model.ModelCatalog;
import java.util.Map;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

class SessionModelControllerTest {
    SessionStore sessions;
    ModelCatalog models;
    SessionModelController controller;
    MockHttpServletRequest request;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:session-model-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(ds);
        sessions = new SessionStore(ds);
        models = new ModelCatalog(ds);
        controller =
                new SessionModelController(
                        sessions, models, new AccessGuard(new AccessCatalog(ds)));
        request = new MockHttpServletRequest();
        request.setAttribute(
                AccountStore.Account.class.getName(),
                new AccountStore.Account("owner", "owner", "admin", 0));
    }

    @Test
    void choosingOneSessionPersistsWithoutChangingTheDefaultOrAnotherSession() throws Exception {
        var first =
                models.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "first", "https://example.invalid/v1", "fake"),
                        null);
        var second =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "second", "https://example.invalid/v1", "fake"),
                        null,
                        "Second",
                        false);
        var a = sessions.create("owner", "A");
        var b = sessions.create("owner", "B");
        assertEquals(
                200,
                controller
                        .select(a.sessionId(), Map.of("endpointId", second.endpointId()), request)
                        .getStatusCode()
                        .value());
        assertEquals(
                second.endpointId(), sessions.find(a.sessionId()).orElseThrow().modelEndpointId());
        assertEquals(first.endpointId(), b.modelEndpointId());
        assertEquals(
                first.endpointId(), sessions.find(b.sessionId()).orElseThrow().modelEndpointId());
        assertEquals(first.endpointId(), models.current("chat").orElseThrow().endpointId());
        models.makeCurrent(second.endpointId());
        assertEquals(
                first.endpointId(), sessions.find(b.sessionId()).orElseThrow().modelEndpointId());
    }

    @Test
    void savedModelSourceResolvesTheSessionEndpointAndRejectsMissingEndpoints() throws Exception {
        var first =
                models.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "first", "https://example.invalid/v1", "fake"),
                        null);
        var second =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "second", "https://example.invalid/v1", "fake"),
                        null,
                        "Second",
                        false);
        var session = sessions.create("owner", "A");
        var source =
                new io.agentscope.advisor.model.SavedChatModelSource(
                        models, new io.agentscope.advisor.model.OpenAiEndpointClient(), sessions);
        assertEquals("first", source.forSession(session.sessionId()).orElseThrow().getModelName());
        sessions.selectModel(session.sessionId(), "owner", second.endpointId());
        assertEquals("second", source.forSession(session.sessionId()).orElseThrow().getModelName());
        assertThrows(IllegalStateException.class, () -> source.forEndpoint("removed-endpoint"));
        assertEquals(first.endpointId(), models.current("chat").orElseThrow().endpointId());
    }

    @Test
    void hidesOtherOwnersSessionsAndRejectsEmbeddingOrMissingModels() throws Exception {
        var other = sessions.create("someone-else", "Other");
        assertEquals(404, controller.get(other.sessionId(), request).getStatusCode().value());
        assertEquals(
                404,
                controller
                        .select(other.sessionId(), Map.of("endpointId", "missing"), request)
                        .getStatusCode()
                        .value());
        var own = sessions.create("owner", "Own");
        assertEquals(
                400,
                controller
                        .select(own.sessionId(), Map.of("endpointId", "missing"), request)
                        .getStatusCode()
                        .value());
        var vector =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "embedding",
                                "custom",
                                "embed",
                                "https://example.invalid/v1",
                                "fake"),
                        8,
                        "Embed",
                        false);
        assertEquals(
                400,
                controller
                        .select(own.sessionId(), Map.of("endpointId", vector.endpointId()), request)
                        .getStatusCode()
                        .value());
    }

    @Test
    void membersCannotEnumerateOrSelectManagementModels() throws Exception {
        var own = sessions.create("owner", "Own");
        request.setAttribute(
                AccountStore.Account.class.getName(),
                new AccountStore.Account("owner", "owner", "member", 0));
        assertEquals(
                java.util.List.of(),
                controller.get(own.sessionId(), request).getBody().get("choices"));
        assertEquals(
                403,
                controller
                        .select(own.sessionId(), Map.of("endpointId", "missing"), request)
                        .getStatusCode()
                        .value());
    }
}
