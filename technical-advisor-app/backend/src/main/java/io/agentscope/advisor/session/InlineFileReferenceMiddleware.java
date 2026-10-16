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
package io.agentscope.advisor.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Expands local file or directory paths pasted/dragged/typed directly in the chat input box (even
 * when not uploaded as attachment chips) by parsing them via {@link SessionAttachments} and
 * appending the extracted text/metadata block and native multimodal image blocks before the message
 * reaches the model.
 */
public final class InlineFileReferenceMiddleware implements HarnessRuntimeMiddleware {

    private final Path workspaceDir;

    public InlineFileReferenceMiddleware(Path workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (workspaceDir == null
                || input == null
                || input.msgs() == null
                || input.msgs().isEmpty()) {
            return next.apply(input);
        }
        List<Msg> rewritten = new ArrayList<>(input.msgs().size());
        boolean changed = false;
        for (Msg msg : input.msgs()) {
            if (msg.getRole() != MsgRole.USER) {
                rewritten.add(msg);
                continue;
            }
            String original = msg.getTextContent();
            String expanded = SessionAttachments.expandInlineFileReferences(original, workspaceDir);
            String effectiveText = expanded != null ? expanded : original;
            List<ImageBlock> imageBlocks =
                    SessionAttachments.extractImageBlocksForMessage(effectiveText, workspaceDir);
            boolean textChanged = expanded != null && !expanded.equals(original);
            if (textChanged || !imageBlocks.isEmpty()) {
                List<ContentBlock> blocks = new ArrayList<>();
                blocks.add(TextBlock.builder().text(effectiveText).build());
                blocks.addAll(imageBlocks);
                rewritten.add(msg.withContent(blocks));
                changed = true;
            } else {
                rewritten.add(msg);
            }
        }
        return next.apply(changed ? new AgentInput(rewritten) : input);
    }
}
