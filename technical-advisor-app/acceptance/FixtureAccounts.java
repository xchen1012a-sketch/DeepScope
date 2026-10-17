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
import io.deepscope.advisor.auth.AccountStore;
import java.time.Clock;
import java.time.Duration;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Creates only a temporary ordinary visitor for real permission acceptance; removes it afterwards. */
class FixtureAccounts {
    public static void main(String[] args) throws Exception {
        String username = System.getenv("ACCEPTANCE_VISITOR_USERNAME");
        if (username == null || !username.startsWith("tds-acceptance-")) {
            throw new IllegalArgumentException("Acceptance username required");
        }
        var source = new SimpleDriverDataSource(new Driver(),
                System.getenv().getOrDefault("ADVISOR_DATABASE_URL", "jdbc:postgresql://localhost:5432/technical_advisor"),
                System.getenv().getOrDefault("ADVISOR_DATABASE_USERNAME", "juvon"),
                System.getenv().getOrDefault("ADVISOR_DATABASE_PASSWORD", ""));
        if (args.length == 1 && args[0].equals("create")) {
            new AccountStore(source, Clock.systemUTC(), Duration.ofHours(1))
                    .createUser(username, System.getenv("ACCEPTANCE_VISITOR_PASSWORD"));
        } else if (args.length == 1 && args[0].equals("delete")) {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("DELETE FROM accounts WHERE username = ? AND role = 'user'")) {
                statement.setString(1, username);
                statement.executeUpdate();
            }
        } else {
            throw new IllegalArgumentException("Expected create or delete");
        }
    }
}
