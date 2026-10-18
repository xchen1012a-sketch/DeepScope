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

package io.deepscope.advisor.agent;

import io.deepscope.advisor.db.JdbcPage;
import io.deepscope.advisor.db.PageResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** A single database page over official agents and creation drafts. */
@Repository
public class AgentOverviewStore {
    private final DataSource dataSource;

    public AgentOverviewStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Item(
            String id,
            String name,
            String description,
            boolean draft,
            boolean enabled,
            String ownerUserId,
            boolean isPublic) {}

    public PageResult<Item> page(
            String userId, boolean allAgents, boolean allDrafts, String search, int page, int size)
            throws SQLException {
        var query =
                new JdbcPage.Query(
                                "SELECT * FROM (SELECT agent_id AS id, name, description,"
                                    + " owner_user_id, 0 AS draft, enabled, is_public FROM"
                                    + " advisor_agents WHERE lifecycle = 'active' UNION ALL SELECT"
                                    + " draft_id AS id, name, COALESCE(NULLIF(purpose, ''),"
                                    + " description) AS description, owner_user_id, 1 AS draft, 0"
                                    + " AS enabled, 0 AS is_public FROM"
                                    + " advisor_agent_creation_drafts WHERE activation_state ="
                                    + " 'draft') overview",
                                "LOWER(name), draft, id")
                        .where(
                                "((draft = 0 AND (? = TRUE OR is_public = 1 OR owner_user_id IS"
                                    + " NULL OR owner_user_id = ?)) OR (draft = 1 AND (? = TRUE OR"
                                    + " owner_user_id = ?)))",
                                allAgents,
                                userId,
                                allDrafts,
                                userId)
                        .search(search, List.of("name", "description"));
        try (var connection = dataSource.getConnection()) {
            return JdbcPage.fetch(
                    connection,
                    query,
                    page,
                    size,
                    statement -> {
                        List<Item> items = new ArrayList<>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next())
                                items.add(
                                        new Item(
                                                rows.getString("id"),
                                                rows.getString("name"),
                                                rows.getString("description"),
                                                rows.getInt("draft") == 1,
                                                rows.getInt("enabled") == 1,
                                                rows.getString("owner_user_id"),
                                                rows.getInt("is_public") == 1));
                        }
                        return items;
                    });
        }
    }
}
