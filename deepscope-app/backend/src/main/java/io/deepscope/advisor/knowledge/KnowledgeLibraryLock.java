/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.deepscope.advisor.knowledge;

import java.sql.Connection;
import java.sql.SQLException;

/** All operations that also touch documents acquire the library row first. */
final class KnowledgeLibraryLock {
    private KnowledgeLibraryLock() {}

    static boolean acquire(Connection connection, String id) throws SQLException {
        try (var lock =
                connection.prepareStatement(
                        "SELECT library_id FROM advisor_knowledge_bases WHERE library_id=? FOR"
                                + " UPDATE")) {
            lock.setQueryTimeout(15);
            lock.setString(1, id);
            try (var rows = lock.executeQuery()) {
                return rows.next();
            }
        }
    }

    static void completeIfReady(Connection connection, String id) throws SQLException {
        try (var update =
                connection.prepareStatement(
                        "UPDATE advisor_knowledge_bases SET index_state='ready' WHERE library_id=?"
                                + " AND index_state='rebuilding' AND NOT EXISTS (SELECT 1 FROM"
                                + " advisor_documents WHERE library_id=? AND status<>'ready')")) {
            update.setString(1, id);
            update.setString(2, id);
            update.executeUpdate();
        }
    }
}
