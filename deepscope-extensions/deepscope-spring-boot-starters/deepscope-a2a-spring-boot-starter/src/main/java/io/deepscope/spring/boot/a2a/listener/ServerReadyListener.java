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

package io.deepscope.spring.boot.a2a.listener;

import io.deepscope.core.a2a.server.DeepScopeA2aServer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Server Ready Listener, used to callback {@link DeepScopeA2aServer#postEndpointReady()}.
 */
public class ServerReadyListener {

    private final DeepScopeA2aServer deepScopeA2aServer;

    public ServerReadyListener(DeepScopeA2aServer deepScopeA2aServer) {
        this.deepScopeA2aServer = deepScopeA2aServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        deepScopeA2aServer.postEndpointReady();
    }
}
