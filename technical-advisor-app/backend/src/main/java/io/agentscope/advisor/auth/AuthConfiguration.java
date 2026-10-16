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
package io.agentscope.advisor.auth;

import io.agentscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean
    public AccountStore accountStore(DataSource dataSource, AuthProperties auth)
            throws SQLException {
        AdvisorSchema.initialize(dataSource);
        AccountStore accounts = new AccountStore(dataSource, Clock.systemUTC(), auth.sessionTtl());
        accounts.bootstrap(auth.bootstrapUsername(), auth.bootstrapPassword());
        return accounts;
    }

    @Bean
    public ApiAuthenticationFilter apiAuthenticationFilter(
            AccountStore accounts,
            @Value("${agentscope.admin.base-path:/v1/admin}") String adminBasePath) {
        return new ApiAuthenticationFilter(accounts, adminBasePath);
    }
}
