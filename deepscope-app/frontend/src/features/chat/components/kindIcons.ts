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

import type { ComponentType } from 'react';
import {
  AlertTriangle,
  Archive,
  BookMarked,
  Bot,
  Brain,
  CheckCircle2,
  FileText,
  GitPullRequest,
  HelpCircle,
  MessageCircle,
  MessageSquare,
  UserCog,
  Users,
  Wrench,
} from 'lucide-react';

/** 每个块类型一个图标：时间线卡片与主对话过程行指的是同一块，用同一套图标。 */
export const KIND_ICON: Record<string, ComponentType<{ className?: string }>> = {
  user_message: MessageSquare,
  thinking: Brain,
  advisor_message: MessageCircle,
  tool_call: Wrench,
  compaction: Archive,
  load_skill: BookMarked,
  dispatch: Users,
  self_task: UserCog,
  subagent_settled: Bot,
  advisor_note: MessageSquare,
  question: HelpCircle,
  deliverable: FileText,
  proposal: GitPullRequest,
  proposal_resolved: CheckCircle2,
  error: AlertTriangle,
};
