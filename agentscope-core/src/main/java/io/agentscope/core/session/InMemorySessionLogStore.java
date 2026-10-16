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
package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.log.AtomicSessionStore;
import io.agentscope.core.session.log.DurableSession;
import io.agentscope.core.session.log.NativeSessionLogStore;
import io.agentscope.core.session.log.SessionEventData;
import io.agentscope.core.session.log.SessionLog;
import io.agentscope.core.session.log.SessionLogStore;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit ephemeral backend for tests and non-persistent embedded applications. */
public final class InMemorySessionLogStore implements SessionLogStore {
    private final MemoryStorage storage = new MemoryStorage();
    private final AtomicSessionStore kernel = new AtomicSessionStore(storage);
    private final NativeSessionLogStore logs = new NativeSessionLogStore(kernel);
    private final ConcurrentHashMap<SessionKey, String> ids = new ConcurrentHashMap<>();

    @Override
    public DurableSession openWriter(
            SessionKey key, String runId, String turnId, RuntimeContext context) {
        return logs.openWriter(storedId(key), runId, turnId);
    }

    @Override
    public void appendExternal(
            SessionKey key, SessionEventData data, String turnId, RuntimeContext context) {
        logs.appendExternal(storedId(key), data, turnId);
    }

    @Override
    public SessionLog readKernel(SessionKey key, RuntimeContext context) {
        return logs.read(storedId(key));
    }

    @Override
    public String owner(SessionKey key, RuntimeContext context) {
        return logs.owner(storedId(key));
    }

    private String storedId(SessionKey key) {
        String id = key.kernelSessionId();
        ids.put(key, id);
        return id;
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        String userId = context == null ? null : context.getUserId();
        return ids.entrySet().stream()
                .filter(entry -> Objects.equals(userId, entry.getKey().userId()))
                .filter(entry -> hasEvents(entry.getValue()))
                .map(entry -> entry.getKey())
                .sorted(
                        Comparator.comparing(SessionKey::agentId)
                                .thenComparing(SessionKey::sessionId))
                .toList();
    }

    private boolean hasEvents(String id) {
        return count(id) > 0 || count(id + "~inbox") > 0;
    }

    private long count(String id) {
        return kernel.list().contains(id) ? kernel.stat(id).eventCount() : 0;
    }

    private static final class MemoryStorage implements AtomicSessionStorage {
        private final ConcurrentHashMap<String, Value> values = new ConcurrentHashMap<>();

        @Override
        public Value read(String path) {
            return values.get(path);
        }

        @Override
        public List<String> listPaths(String prefix) {
            return values.keySet().stream()
                    .filter(path -> path.startsWith(prefix))
                    .sorted()
                    .toList();
        }

        @Override
        public synchronized boolean compareAndSet(String path, long expected, byte[] bytes) {
            var current = values.get(path);
            if ((current == null ? 0 : current.version()) != expected) return false;
            values.put(path, new Value(expected + 1, bytes));
            return true;
        }
    }
}
