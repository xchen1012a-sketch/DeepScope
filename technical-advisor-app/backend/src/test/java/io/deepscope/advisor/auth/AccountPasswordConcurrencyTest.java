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
package io.deepscope.advisor.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class AccountPasswordConcurrencyTest {
    @Test
    void aRevocationFailureRollsBackThePasswordChange() throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:password-rollback-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var accounts = new AccountStore(source, Clock.systemUTC(), Duration.ofHours(12));
        accounts.createUser("user", "old-test-password");
        var current = accounts.login("user", "old-test-password").orElseThrow();
        var other = accounts.login("user", "old-test-password").orElseThrow();
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TRIGGER refuse_revoke BEFORE DELETE ON login_sessions FOR EACH ROW CALL"
                            + " '"
                            + RefuseRevocation.class.getName()
                            + "'");
        }
        assertThrows(
                SQLException.class,
                () ->
                        accounts.changePassword(
                                current.account().userId(),
                                "old-test-password",
                                "new-test-password",
                                current.token()));
        assertTrue(accounts.verifyPassword(current.account().userId(), "old-test-password"));
        assertTrue(accounts.authenticate(other.token()).isPresent());
        assertTrue(accounts.authenticate(current.token()).isPresent());
    }

    public static final class RefuseRevocation implements org.h2.api.Trigger {
        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow)
                throws SQLException {
            throw new SQLException("Test-only revocation failure");
        }
    }

    @Test
    void aLoginAlreadyVerifiedAgainstTheOldPasswordCannotSurviveThePasswordChange()
            throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:password-race-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var accounts = new AccountStore(source, Clock.systemUTC(), Duration.ofHours(12));
        accounts.createUser("user", "old-test-password");
        var current = accounts.login("user", "old-test-password").orElseThrow();
        var verified = new CountDownLatch(1);
        var releaseInsert = new CountDownLatch(1);
        var delayedSource =
                new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        var connection = super.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, arguments) -> {
                                            Object result = invoke(connection, method, arguments);
                                            if (method.getName().equals("prepareStatement")
                                                    && arguments[0] instanceof String sql
                                                    && sql.startsWith(
                                                            "INSERT INTO login_sessions")) {
                                                return Proxy.newProxyInstance(
                                                        PreparedStatement.class.getClassLoader(),
                                                        new Class<?>[] {PreparedStatement.class},
                                                        (statement, call, values) -> {
                                                            if (call.getName()
                                                                    .equals("executeUpdate")) {
                                                                verified.countDown();
                                                                if (!releaseInsert.await(
                                                                        5, TimeUnit.SECONDS))
                                                                    throw new SQLException(
                                                                            "Test insert barrier"
                                                                                    + " timed out");
                                                            }
                                                            return invoke(result, call, values);
                                                        });
                                            }
                                            return result;
                                        });
                    }
                };
        var delayedAccounts =
                new AccountStore(delayedSource, Clock.systemUTC(), Duration.ofHours(12));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var loggingIn =
                    executor.submit(
                            () -> delayedAccounts.login("user", "old-test-password").orElseThrow());
            assertTrue(verified.await(5, TimeUnit.SECONDS));
            var changing =
                    executor.submit(
                            () ->
                                    accounts.changePassword(
                                            current.account().userId(),
                                            "old-test-password",
                                            "new-test-password",
                                            current.token()));
            try {
                // Old code commits here; fixed code waits for the login's account lock.
                changing.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException waitingForAccountLock) {
                // Release the login, then the password transaction must revoke its new session.
            } finally {
                releaseInsert.countDown();
            }
            assertTrue(changing.get(5, TimeUnit.SECONDS));
            var concurrentLogin = loggingIn.get(5, TimeUnit.SECONDS);
            assertTrue(
                    accounts.authenticate(concurrentLogin.token()).isEmpty(),
                    "a session created using the old password must be revoked");
            assertTrue(accounts.authenticate(current.token()).isPresent());
        } finally {
            releaseInsert.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
