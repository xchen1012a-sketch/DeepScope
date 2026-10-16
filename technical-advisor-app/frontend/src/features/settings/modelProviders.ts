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

import type { ModelKind, ModelProvider } from '@/api/model';

interface ProviderPreset { baseUrl: string; keysUrl?: string; chat: string[]; embedding: string[] }
export const providerPresets: Record<ModelProvider, ProviderPreset> = {
  openai: { baseUrl: 'https://api.openai.com/v1', keysUrl: 'https://platform.openai.com/api-keys', chat: ['gpt-4.1', 'gpt-4.1-mini'], embedding: ['text-embedding-3-small', 'text-embedding-3-large'] },
  anthropic: { baseUrl: 'https://api.anthropic.com', keysUrl: 'https://console.anthropic.com/settings/keys', chat: ['claude-sonnet-4-5', 'claude-opus-4-1'], embedding: [] },
  dashscope: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', keysUrl: 'https://bailian.console.aliyun.com/', chat: ['qwen-plus', 'qwen-turbo', 'qwen-max'], embedding: ['text-embedding-v3'] },
  custom: { baseUrl: '', chat: [], embedding: [] },
};
export function availableProviders(kind: ModelKind): ModelProvider[] {
  return (Object.keys(providerPresets) as ModelProvider[]).filter(p => kind === 'chat' || p !== 'anthropic');
}
