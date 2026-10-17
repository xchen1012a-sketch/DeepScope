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
package io.deepscope.advisor.tool;

import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.permission.PermissionContextState;
import io.deepscope.core.permission.PermissionDecision;
import io.deepscope.core.tool.AgentTool;
import io.deepscope.core.tool.ToolBase;
import io.deepscope.core.tool.ToolCallParam;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Live catalog authorization, while the existing MCP tool owns protocol and permission semantics. */
public final class GuardedCatalogTool extends ToolBase {
    private final AgentTool delegate;
    private final Runnable preflight;

    public GuardedCatalogTool(AgentTool delegate, Runnable preflight) {
        this(delegate, preflight, "allow");
    }

    public GuardedCatalogTool(AgentTool delegate, Runnable preflight, String permission) {
        super(
                builder()
                        .name(delegate.getName())
                        .description(
                                enrichedDescription(
                                        delegate.getDescription(), delegate.getName(), permission))
                        .inputSchema(delegate.getParameters())
                        .readOnly(delegate.isReadOnly())
                        .concurrencySafe(false)
                        .mcp(
                                delegate instanceof io.deepscope.core.tool.mcp.McpTool mcp
                                        ? mcp.getClientName()
                                        : "")
                        .returnDirect(delegate.isReturnDirect()));
        this.delegate = delegate;
        this.preflight = preflight;
    }

    private static String enrichedDescription(String originalDesc, String name, String permission) {
        String category =
                "ask".equalsIgnoreCase(permission) ? "【业务分类：高危写操作类（需人工审核）】" : "【业务分类：业务查询与操作类（免审）】";
        String auditRule =
                "ask".equalsIgnoreCase(permission)
                        ? "【审核规则】：调用本工具将触发人工确认审核卡片，需访客在界面点击允许后才可真正执行。"
                        : "【审核规则】：本工具为只读或安全操作，免人工审核。";
        String base = originalDesc == null || originalDesc.isBlank() ? name : originalDesc;
        return category + "\n" + base + "\n" + auditRule;
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Boolean getDeferLoading() {
        return delegate.getDeferLoading();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean matchRule(String rule, Map<String, Object> input) {
        return delegate instanceof ToolBase tool
                ? tool.matchRule(rule, input)
                : super.matchRule(rule, input);
    }

    @Override
    public java.util.List<io.deepscope.core.permission.PermissionRule> generateSuggestions(
            Map<String, Object> input) {
        return delegate instanceof ToolBase tool
                ? tool.generateSuggestions(input)
                : super.generateSuggestions(input);
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState context) {
        return delegate instanceof ToolBase tool
                ? tool.checkPermissions(input, context)
                : super.checkPermissions(input, context);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(
                        () -> {
                            preflight.run();
                            return delegate.callAsync(param);
                        })
                .onErrorResume(
                        ToolProblem.class,
                        error -> Mono.just(ToolResultBlock.error(error.getMessage())));
    }
}
