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
package io.deepscope.advisor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.session.SessionCompactionService;
import io.deepscope.advisor.session.SessionStore;
import io.deepscope.advisor.session.TimelineStore;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

class CommandControllerTest {

    private CommandCatalog commands;
    private SessionStore sessions;
    private SessionCompactionService compactionService;
    private CommandController controller;
    private AccountStore.Account alice;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:cmd-ctrl-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        alice =
                new AccountStore.Account(
                        "user-alice", "alice", "admin", System.currentTimeMillis());
        commands = new CommandCatalog(dataSource);
        commands.ensureBuiltin();
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        TimelineStore timeline = new TimelineStore(dataSource, sessions, json);
        compactionService =
                new SessionCompactionService(sessions, timeline, new AguiAgentRegistry(), json);
        controller = new CommandController(commands, sessions, compactionService, timeline);
    }

    @Test
    void listAvailableReturnsBuiltinForVisitor() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        List<CommandController.CommandDto> list = controller.listAvailable(request);
        assertNotNull(list);
        assertTrue(list.stream().anyMatch(c -> "compact".equals(c.code())));
        assertTrue(list.stream().anyMatch(c -> "clear".equals(c.code())));
    }

    @Test
    void unknownCommandReturns404() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<CommandController.ExecuteResponse> res =
                controller.execute(
                        "unknown_cmd", new CommandController.ExecuteRequest("s1", null), request);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    @Test
    void executeCompressOnSomeoneElsesSessionIsNotFound() throws Exception {
        SessionStore.SessionRow session = sessions.create("other-user", "会话");
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Request authenticated as alice
        request.setAttribute(AccountStore.Account.class.getName(), alice);

        ResponseEntity<CommandController.ExecuteResponse> res =
                controller.execute(
                        "compact",
                        new CommandController.ExecuteRequest(session.sessionId(), null),
                        request);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    @Test
    void executeCompressOnOwnShortSessionReturnsSkipped() throws Exception {
        SessionStore.SessionRow session = sessions.create(alice.userId(), "alice的短会话");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AccountStore.Account.class.getName(), alice);

        ResponseEntity<CommandController.ExecuteResponse> res =
                controller.execute(
                        "compact",
                        new CommandController.ExecuteRequest(session.sessionId(), null),
                        request);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("skipped", res.getBody().outcome());
        assertEquals(SessionCompactionService.REASON_TOO_SHORT, res.getBody().reason());
    }

    @Test
    void executeClearOnOwnSessionClearsContext() throws Exception {
        SessionStore.SessionRow session = sessions.create(alice.userId(), "alice的会话");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AccountStore.Account.class.getName(), alice);

        ResponseEntity<CommandController.ExecuteResponse> res =
                controller.execute(
                        "clear",
                        new CommandController.ExecuteRequest(session.sessionId(), null),
                        request);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("cleared", res.getBody().outcome());
    }

    @Test
    void executeClearOnSomeoneElsesSessionIsNotFound() throws Exception {
        SessionStore.SessionRow session = sessions.create("other-user", "会话");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AccountStore.Account.class.getName(), alice);

        ResponseEntity<CommandController.ExecuteResponse> res =
                controller.execute(
                        "clear",
                        new CommandController.ExecuteRequest(session.sessionId(), null),
                        request);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }
}
