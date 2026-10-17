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
package io.deepscope.harness.agent.verification;

import io.deepscope.core.state.State;
import io.deepscope.core.state.TaskRequirement;
import io.deepscope.core.state.TaskVerification;

/** Audit record retaining the criterion as checked, even after the active task switches. */
public record StoredVerification(
        TaskVerification verification, TaskRequirement criterion, String actionRecordKey)
        implements State {}
