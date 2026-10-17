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
package io.deepscope.harness.agent.session;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.session.AtomicSessionStorage;
import io.deepscope.core.session.SessionKey;
import io.deepscope.core.session.log.AtomicSessionStore;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.NativeSessionLogStore;
import io.deepscope.core.session.log.SessionEventData;
import io.deepscope.core.session.log.SessionLogStore;
import io.deepscope.harness.agent.filesystem.AbstractFilesystem;
import io.deepscope.harness.agent.filesystem.local.LocalFilesystem;
import io.deepscope.harness.agent.workspace.WorkspaceManager;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Default log storage uses the workspace backend, including its distributed namespace routing. */
public final class WorkspaceSessionLogStore implements SessionLogStore {
    private final AbstractFilesystem filesystem;

    /**
     * One kernel store per journal, shared process-wide.
     *
     * <p>Writer ownership lives in an in-memory map held by each {@link NativeSessionLogStore}, so
     * two stores over the same journal would each believe they are the only writer. Independent
     * backend instances — a second agent, or a reopened workspace — must still exclude each other,
     * so the cache is keyed by the storage identity and kept static, mirroring the path-keyed lock
     * registry that {@code LocalSessionStorage} already uses for cross-instance exclusion.
     */
    private static final ConcurrentHashMap<AtomicSessionStorage, NativeSessionLogStore> KERNELS =
            new ConcurrentHashMap<>();

    public WorkspaceSessionLogStore(AbstractFilesystem filesystem) {
        this.filesystem = Objects.requireNonNull(filesystem);
    }

    public WorkspaceSessionLogStore(WorkspaceManager workspace) {
        this(
                workspace.getFilesystem() != null
                        ? workspace.getFilesystem()
                        : new LocalFilesystem(workspace.getWorkspace()));
    }

    /**
     * Discover in the namespace selected by the filesystem and the explicit caller identity.
     * SESSION isolation (including USER fallback without a user ID) only exposes the current
     * namespace. Use a shared user-scoped log backend for cross-session discovery. A baked
     * filesystem still requires the matching user ID here because logical keys are user-scoped.
     */
    @Override
    public List<SessionKey> list(RuntimeContext context) {
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        var kernel = new AtomicSessionStore(filesystem.sessionStorage(rc));
        return kernel.list().stream()
                .map(SessionKey::fromKernelSessionId)
                .filter(Objects::nonNull)
                .filter(
                        key ->
                                kernel.stat(key.kernelSessionId()).eventCount() > 0
                                        || kernel.list().contains(key.kernelSessionId() + "~inbox")
                                                && kernel.stat(key.kernelSessionId() + "~inbox")
                                                                .eventCount()
                                                        > 0)
                .filter(key -> Objects.equals(key.userId(), normalizeUser(rc.getUserId())))
                .distinct()
                .sorted(
                        Comparator.comparing(SessionKey::agentId)
                                .thenComparing(SessionKey::sessionId))
                .toList();
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private NativeSessionLogStore kernel(RuntimeContext context) {
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        AtomicSessionStorage storage = filesystem.sessionStorage(rc);
        return KERNELS.computeIfAbsent(
                storage, ignored -> new NativeSessionLogStore(new AtomicSessionStore(storage)));
    }

    @Override
    public DurableSession openWriter(
            SessionKey key, String runId, String turnId, RuntimeContext context) {
        String stored =
                new SessionKey(key.agentId(), key.agentId(), key.kernelSessionId()).sessionId();
        return kernel(context).openWriter(stored, runId, turnId);
    }

    @Override
    public void appendExternal(
            SessionKey key, SessionEventData data, String turnId, RuntimeContext context) {
        kernel(context)
                .appendExternal(
                        new SessionKey(key.agentId(), key.agentId(), key.kernelSessionId())
                                .sessionId(),
                        data,
                        turnId);
    }

    @Override
    public io.deepscope.core.session.log.SessionLog readKernel(
            SessionKey key, RuntimeContext context) {
        return kernel(context)
                .read(
                        new SessionKey(key.agentId(), key.agentId(), key.kernelSessionId())
                                .sessionId());
    }

    @Override
    public String owner(SessionKey key, RuntimeContext context) {
        return kernel(context)
                .owner(
                        new SessionKey(key.agentId(), key.agentId(), key.kernelSessionId())
                                .sessionId());
    }
}
