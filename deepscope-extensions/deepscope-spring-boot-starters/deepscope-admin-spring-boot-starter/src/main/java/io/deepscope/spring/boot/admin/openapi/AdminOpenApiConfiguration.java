/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.spring.boot.admin.openapi;

import io.deepscope.spring.boot.admin.command.AdminCommand;
import io.deepscope.spring.boot.admin.command.AdminCommandRegistry;
import io.deepscope.spring.boot.admin.command.CommandPlane;
import io.deepscope.spring.boot.admin.properties.AdminProperties;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the admin starter into springdoc-openapi <em>only when springdoc is on the classpath</em>.
 *
 * <p>Consumers opt in by adding {@code springdoc-openapi-starter-webmvc-ui} to their app pom (it
 * is declared as {@code optional} here). When present:
 *
 * <ul>
 *   <li>A {@link GroupedOpenApi} named {@code deepscope-admin} surfaces only the admin REST
 *       routes, so the admin surface shows up as a separate tab in Swagger UI.
 *   <li>An {@link OpenAPI} bean (when none is present) sets sensible title / description /
 *       version metadata and registers one OpenAPI {@link Tag} per {@code AdminCommand}
 *       {@code category} — so Swagger UI groups routes by AgentStateStore / Agent / System.
 * </ul>
 *
 * <p>Loaded lazily by {@link io.deepscope.spring.boot.admin.DeepscopeAdminAutoConfiguration};
 * marked {@link ConditionalOnClass} on the springdoc tag so the class never fails to load when
 * springdoc is absent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({GroupedOpenApi.class, OpenAPI.class})
public class AdminOpenApiConfiguration {

    /** Group all admin routes under their own Swagger UI definition. */
    @Bean
    public GroupedOpenApi deepscopeAdminOpenApi(AdminProperties properties) {
        String base = properties.getBasePath();
        return GroupedOpenApi.builder()
                .group("deepscope-admin")
                .displayName("DeepScope Admin")
                .pathsToMatch(base + "/**")
                .pathsToExclude(base + "/internal/**")
                .build();
    }

    /**
     * Provide a default top-level {@link OpenAPI} bean if the consumer has not declared one.
     * Tags are seeded from the {@link AdminCommandRegistry} so each category AgentStateStore/Agent/System
     * gets its own labelled group in Swagger UI.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI deepscopeAdminOpenApiDefinition(
            AdminProperties properties, AdminCommandRegistry registry) {
        Set<String> categories = new LinkedHashSet<>();
        for (AdminCommand cmd : registry.list()) {
            if (cmd.plane() == CommandPlane.DATA) {
                categories.add(cmd.category());
            }
        }
        List<Tag> tags = new ArrayList<>();
        for (String c : categories) {
            tags.add(new Tag().name(c).description("DeepScope admin — " + c));
        }
        return new OpenAPI()
                .info(
                        new Info()
                                .title("DeepScope Admin API")
                                .version("v1")
                                .description(
                                        "Per-session admin / ops actions over deepscope-java"
                                            + " agents. Discover the full command catalog at GET"
                                            + " /actuator/deepscope-commands."))
                .externalDocs(
                        new ExternalDocumentation()
                                .description("Plan & gap analysis")
                                .url(
                                        "https://github.com/xchen1012a-sketch/DeepScope/blob/main/ADMIN_OPS_API_PLAN.md"))
                .addServersItem(
                        new Server().url(properties.getBasePath()).description("Admin base path"))
                .tags(tags);
    }
}
