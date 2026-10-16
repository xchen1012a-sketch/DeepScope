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

import { payloadText, type EventEntry } from '@/api/advisor';
import type { ReasoningEffort } from './aguiClient';

export interface ToolCall {
  id: string;
  name: string;
  args: string;
  result?: string;
  status: 'running' | 'done' | 'failed';
  /** 起始时间来自 `TOOL_CALL_START` 那一刻的记录；老数据没有这个字段时是 null。 */
  startedAt: number | null;
  endedAt?: number;
  /** 起止都已知才有耗时；缺一个就是 null，读侧据此不显示，而不是显示 0。 */
  durationMs?: number | null;
}

export interface ToolSignal {
  id: string;
  name?: string;
  argsDelta?: string;
  result?: string;
  /** 工具以失败态结束（提供方返回 error/denied/interrupted 之一）。 */
  failed?: boolean;
  startedAt?: number;
  endedAt?: number;
}

export interface UsageSample {
  stepId: string;
  uncachedInput: number;
  cacheRead: number;
  cacheWrite: number;
  output: number;
  contextWindow?: number;
  /** 该步模型调用实际使用的模型名；由应用层随 token_usage 透出，缺失时不写。 */
  modelName?: string;
  projectedTokens?: number;
  timeSeconds?: number;
  firstTokenMs?: number;
  outputTokensPerSecond?: number;
}

/** 上下文面板的三行构成；三行是按本次请求估算的，与占用百分比不要求对齐。 */
export interface ContextBreakdown {
  systemPromptTokens: number;
  toolTokens: number;
  messageTokens: number;
}

/** 主智能体的系统提示长度按仓库里的工作区说明量级估一个常数。 */
const SYSTEM_PROMPT_ESTIMATE_TOKENS = 1_200;
/** 单个工具 schema 的大致开销。 */
const TOOL_SCHEMA_ESTIMATE_TOKENS = 120;

/**
 * 系统提示、工具、消息三行估算。应用层只拿得到「本次提示的 token 总量」与已发生的
 * 工具调用次数，拿不到系统提示与工具 schema 的原文，所以这里按可观测的部分估：
 * 系统提示与工具行按固定估算，消息行取剩余量。面板上必须标明这是估算。
 */
export function contextBreakdown(projectedTokens?: number, toolCalls = 0): ContextBreakdown | null {
  if (projectedTokens === undefined || projectedTokens <= 0) return null;
  const systemPromptTokens = Math.min(SYSTEM_PROMPT_ESTIMATE_TOKENS, projectedTokens);
  const toolTokens = Math.min(toolCalls * TOOL_SCHEMA_ESTIMATE_TOKENS, projectedTokens - systemPromptTokens);
  return {
    systemPromptTokens,
    toolTokens,
    messageTokens: Math.max(0, projectedTokens - systemPromptTokens - toolTokens),
  };
}

export interface CacheHit {
  percent: string;
  uncachedInput: number;
  cacheRead: number;
  cacheWrite: number;
  output: number;
}

export interface ContextOccupancy {
  used: number;
  capacity: number;
  percent: number;
}

export interface ContextRemaining {
  used: number;
  remaining: number;
  capacity: number;
  percent: number;
  remainingPercent: number;
}

/** 默认上下文窗口：当所有字典查询与探测均未知时回退到 128K（131,072）。 */
export const DEFAULT_CONTEXT_WINDOW = 131_072;

const KNOWN_WINDOWS: Record<string, number> = {
  // DeepSeek
  'deepseek-chat': 131_072,
  'deepseek-reasoner': 131_072,
  'deepseek-v3': 131_072,
  'deepseek-r1': 131_072,
  'deepseek-coder': 131_072,

  // OpenAI
  'gpt-4o': 128_000,
  'gpt-4o-mini': 128_000,
  'gpt-4o-realtime-preview': 128_000,
  'gpt-4o-audio-preview': 128_000,
  'chatgpt-4o-latest': 128_000,
  'o1': 200_000,
  'o1-mini': 128_000,
  'o1-preview': 128_000,
  'o3': 200_000,
  'o3-mini': 200_000,
  'o4-mini': 200_000,
  'gpt-4-turbo': 128_000,
  'gpt-4-turbo-preview': 128_000,
  'gpt-4-1106-preview': 128_000,
  'gpt-4-0125-preview': 128_000,
  'gpt-4': 8_192,
  'gpt-4-32k': 32_768,
  'gpt-3.5-turbo': 16_385,
  'gpt-3.5-turbo-16k': 16_385,

  // DashScope / Tongyi
  'qwen-turbo': 1_000_000,
  'qwen-turbo-latest': 1_000_000,
  'qwen-plus': 131_072,
  'qwen-plus-latest': 131_072,
  'qwen-max': 32_768,
  'qwen-max-latest': 32_768,
  'qwen-long': 10_000_000,
  'qwen2.5-72b-instruct': 131_072,
  'qwen2.5-32b-instruct': 131_072,
  'qwen2.5-14b-instruct': 131_072,
  'qwen2.5-7b-instruct': 131_072,
  'qwen2.5-coder-32b-instruct': 131_072,
  'qwen2.5-coder-7b-instruct': 131_072,
  'qwen-vl-max': 32_768,
  'qwen-vl-plus': 32_768,

  // Anthropic Claude
  'claude-3-7-sonnet': 200_000,
  'claude-3-5-sonnet': 200_000,
  'claude-3-5-haiku': 200_000,
  'claude-3-opus': 200_000,
  'claude-3-sonnet': 200_000,
  'claude-3-haiku': 200_000,

  // Google Gemini
  'gemini-1.5-pro': 2_000_000,
  'gemini-1.5-flash': 1_000_000,
  'gemini-1.5-flash-8b': 1_000_000,
  'gemini-2.0-flash': 1_048_576,
  'gemini-2.0-flash-exp': 1_048_576,
  'gemini-2.0-flash-thinking-exp': 1_048_576,
  'gemini-2.5-pro': 2_000_000,
  'gemini-2.5-flash': 1_000_000,

  // Moonshot / Kimi
  'moonshot-v1-8k': 8_192,
  'moonshot-v1-32k': 32_768,
  'moonshot-v1-128k': 131_072,
  'kimi-k1.5': 131_072,

  // Zhipu GLM
  'glm-4': 128_000,
  'glm-4-plus': 128_000,
  'glm-4-air': 128_000,
  'glm-4-flash': 128_000,
  'glm-4-long': 1_000_000,

  // MiniMax
  'abab6.5s-chat': 245_760,
  'abab6.5t-chat': 245_760,
  'abab6.5g-chat': 8_192,

  // Baichuan
  'baichuan4': 32_768,
  'baichuan3-turbo': 32_768,

  // Doubao
  'doubao-pro-32k': 32_768,
  'doubao-pro-128k': 131_072,
  'doubao-pro-256k': 256_000,
  'doubao-lite-32k': 32_768,
  'doubao-lite-128k': 131_072,

  // Yi
  'yi-large': 32_768,
  'yi-medium': 16_384,
  'yi-lightning': 16_384,
};

function normalizeModelName(raw?: string): string {
  if (!raw) return '';
  const trimmed = raw.trim().toLowerCase();
  const slashIdx = trimmed.lastIndexOf('/');
  const base = slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : trimmed;
  return base.replace(/_/g, '-');
}

/**
 * 依据模型名推断上下文窗口大小：
 * 1. 字典精确 / 别名匹配
 * 2. 常见后缀推断（如 -128k, -32k, -1m）
 * 3. 系列关键字启发式推断（deepseek -> 128K, gpt-4o -> 128K, o1/o3 -> 200K, claude -> 200K, gemini -> 1M）
 * 4. 兜底回退 DEFAULT_CONTEXT_WINDOW (128K)
 */
export function inferContextWindow(modelName?: string): number {
  if (!modelName) return DEFAULT_CONTEXT_WINDOW;
  const normalized = normalizeModelName(modelName);
  if (!normalized) return DEFAULT_CONTEXT_WINDOW;

  if (KNOWN_WINDOWS[normalized]) {
    return KNOWN_WINDOWS[normalized];
  }

  // Token suffix regex (e.g. -128k, -32k, -1m)
  const suffixMatch = normalized.match(/(?:^|[-_])(\d+)(k|m)(?:[-_]|$)/i);
  if (suffixMatch) {
    const num = parseInt(suffixMatch[1], 10);
    const unit = suffixMatch[2].toLowerCase();
    if (!isNaN(num) && num > 0) {
      if (unit === 'k') {
        if (num === 128) return 131_072;
        if (num === 64) return 65_536;
        if (num === 32) return 32_768;
        if (num === 16) return 16_384;
        if (num === 8) return 8_192;
        return num * 1024;
      }
      if (unit === 'm') {
        return num * 1_000_000;
      }
    }
  }

  // Family heuristics
  if (normalized.includes('deepseek')) return 131_072;
  if (normalized.includes('o1') || normalized.includes('o3') || normalized.includes('o4')) return 200_000;
  if (normalized.includes('gpt-4o') || normalized.includes('gpt-4-turbo')) return 128_000;
  if (normalized.includes('gpt-4')) return 8_192;
  if (normalized.includes('gpt-3.5')) return 16_385;
  if (normalized.includes('claude-3') || normalized.includes('claude-2') || normalized.includes('claude')) return 200_000;
  if (normalized.includes('gemini-1.5') || normalized.includes('gemini-2') || normalized.includes('gemini')) return 1_000_000;
  if (normalized.includes('qwen-max')) return 32_768;
  if (normalized.includes('qwen-turbo')) return 1_000_000;
  if (normalized.includes('qwen-long')) return 10_000_000;
  if (normalized.includes('qwen')) return 131_072;
  if (normalized.includes('glm-4-long')) return 1_000_000;
  if (normalized.includes('glm-4') || normalized.includes('glm')) return 128_000;
  if (normalized.includes('llama-3.1') || normalized.includes('llama-3.2') || normalized.includes('llama-3.3')) return 131_072;
  if (normalized.includes('llama-3')) return 8_192;
  if (normalized.includes('mistral') || normalized.includes('codestral')) return 32_768;

  return DEFAULT_CONTEXT_WINDOW;
}

/** 思考行摘要：流出中取最后一行非空文字，落定后取第一行。 */
export function thinkLine(text: string, streaming: boolean): string {
  const preview = streaming ? text.slice(-1024) : text.slice(0, 1024);
  const lines = preview
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (lines.length === 0) return '';
  return streaming ? lines[lines.length - 1] : lines[0];
}

/** 同一次工具调用合并成一行。结果到达前保持进行中。 */
export function foldToolCall(calls: ToolCall[], signal: ToolSignal): ToolCall[] {
  const existing = calls.find((call) => call.id === signal.id);
  const startedAt = existing?.startedAt ?? signal.startedAt ?? null;
  const endedAt = signal.endedAt ?? existing?.endedAt;
  const next: ToolCall = existing
    ? {
        ...existing,
        name: signal.name || existing.name,
        args: existing.args + (signal.argsDelta ?? ''),
        startedAt,
        endedAt,
      }
    : {
        id: signal.id,
        name: signal.name || signal.id,
        args: signal.argsDelta ?? '',
        status: 'running',
        startedAt,
      };
  next.durationMs = startedAt !== null && endedAt !== undefined ? Math.max(0, endedAt - startedAt) : null;
  if (signal.result !== undefined || signal.failed) {
    next.status = signal.failed ? 'failed' : 'done';
    next.result = signal.result ?? next.result;
    next.endedAt = endedAt;
  }
  return existing ? calls.map((call) => (call.id === signal.id ? next : call)) : [...calls, next];
}

/**
 * 同一步的后到用量替换先到的用量。提供方只在部分样本上带上下文窗口，
 * 替换时不得把已经拿到的窗口清空——缺窗口的老样本与缺窗口的新样本都不写这个字段。
 */
export function foldUsage(existing: UsageSample[], next: UsageSample): UsageSample[] {
  const index = existing.findIndex((sample) => sample.stepId === next.stepId);
  if (index < 0) return [...existing, next];
  return existing.map((sample, position) => {
    if (position !== index) return sample;
    const merged = { ...next };
    if (merged.contextWindow === undefined && sample.contextWindow !== undefined) {
      merged.contextWindow = sample.contextWindow;
    }
    if (merged.modelName === undefined && sample.modelName !== undefined) {
      merged.modelName = sample.modelName;
    }
    if (merged.firstTokenMs === undefined && sample.firstTokenMs !== undefined) {
      merged.firstTokenMs = sample.firstTokenMs;
    }
    return merged;
  });
}

/** 缓存命中。提供方没有回报缓存读入和写出时不显示。输出不计入分母。 */
export function cacheHit(samples: UsageSample[]): CacheHit | null {
  const totals = samples.reduce(
    (sum, sample) => ({
      uncachedInput: sum.uncachedInput + sample.uncachedInput,
      cacheRead: sum.cacheRead + sample.cacheRead,
      cacheWrite: sum.cacheWrite + sample.cacheWrite,
      output: sum.output + sample.output,
    }),
    { uncachedInput: 0, cacheRead: 0, cacheWrite: 0, output: 0 },
  );
  if (totals.cacheRead === 0 && totals.cacheWrite === 0) return null;
  const billed = totals.uncachedInput + totals.cacheRead + totals.cacheWrite;
  if (billed <= 0) return null;
  return {
    ...totals,
    percent: ((totals.cacheRead / billed) * 100).toFixed(1),
  };
}

/**
 * 取最新模型的连续采样范围内最后一个已知窗口；缺失的后续采样可以沿用同模型窗口，
 * 但遇到其他模型就停止，不能将之前模型的容量借给未知的新模型。
 */
export function contextWindowOf(samples: UsageSample[]): number | undefined {
  let modelName = samples[samples.length - 1]?.modelName;
  for (let index = samples.length - 1; index >= 0; index -= 1) {
    const sample = samples[index];
    if (!modelName && sample.modelName) modelName = sample.modelName;
    if (modelName && sample.modelName && sample.modelName !== modelName) return undefined;
    if (sample.contextWindow !== undefined && (!modelName || sample.modelName === modelName)) return sample.contextWindow;
  }
  return undefined;
}

/** 上下文环。用量或窗口缺一个就不画。 */
export function contextOccupancy(projected?: number, window?: number): ContextOccupancy | null {
  if (projected === undefined || window === undefined || window <= 0) return null;
  return { used: projected, capacity: window, percent: Math.round((projected / window) * 100) };
}

/**
 * 上下文剩余量计算：始终返回有效快照（未上报窗口时回退到 DEFAULT_CONTEXT_WINDOW，
 * 新会话未产生采样时剩余 100%），供输入栏圆圈在点击或悬浮时随时展示详情。
 */
export function contextRemaining(projected?: number, window?: number): ContextRemaining {
  const capacity = window !== undefined && window > 0 ? window : DEFAULT_CONTEXT_WINDOW;
  const used = Math.max(0, projected ?? 0);
  const remaining = Math.max(0, capacity - used);
  const percent = Math.min(100, Math.round((used / capacity) * 100));
  const remainingPercent = Math.max(0, 100 - percent);
  return { used, remaining, capacity, percent, remainingPercent };
}

/** 不到 1 秒显示毫秒，1~10 秒保留一位有效小数，不到一分钟显示秒，否则显示分和秒。 */
export function formatRunDuration(ms: number | null): string | null {
  if (ms === null || ms < 0) return null;
  if (ms < 1000) return `${Math.max(1, Math.round(ms))}ms`;
  if (ms < 10_000) {
    const tenths = Math.round(ms / 100) / 10;
    return Number.isInteger(tenths) ? `${tenths}s` : `${tenths.toFixed(1)}s`;
  }
  const totalSeconds = Math.round(ms / 1000);
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

/** 已结束用落定时间，进行中用当前时间。没有派发时间则没有耗时。 */
export function subagentElapsed(start: number | undefined, end: number | undefined, now: number): number | null {
  if (start === undefined) return null;
  const stop = end ?? now;
  return Math.max(0, stop - start);
}

/**
 * 子智能体耗时：进行中算到现在，已结束用落定时间减去派发时间。
 * 没有派发时间、或已结束却没有落定时间时不显示，也不显示成 0 秒。
 */
export function subagentDurationMs(
  record: { status: string; startedAt?: number; endedAt?: number },
  now: number,
): number | null {
  if (record.startedAt === undefined) return null;
  if (record.status === 'running') return Math.max(0, now - record.startedAt);
  if (record.endedAt === undefined) return null;
  return Math.max(0, record.endedAt - record.startedAt);
}

/**
 * 一条 tool_call 事件还原成工具调用。起始时间只认落库的 `payload.startedAt`：
 * 事件行的 `createdAt` 是回合结束时服务端追加的时间，拿它当起始时间会让耗时接近 0，
 * 因此缺失时宁可不显示耗时。
 */
export function toolFromEvent(event: EventEntry): ToolCall {
  const status = payloadText(event.payload, 'status');
  const startedAt = typeof event.payload.startedAt === 'number' ? event.payload.startedAt : null;
  const endedAt = typeof event.payload.endedAt === 'number' ? event.payload.endedAt : undefined;
  return {
    id: payloadText(event.payload, 'id') || String(event.seq),
    name: payloadText(event.payload, 'name') || payloadText(event.payload, 'id'),
    args: payloadText(event.payload, 'args'),
    result: payloadText(event.payload, 'result') || undefined,
    status: status === 'failed' ? 'failed' : status === 'done' ? 'done' : 'running',
    startedAt,
    endedAt,
    durationMs: startedAt !== null && endedAt !== undefined ? Math.max(0, endedAt - startedAt) : null,
  };
}

/**
 * 时间线上的时钟时间（时:分:秒）。时间线卡片右侧只放这个，不放令牌与缓存数字。
 */
export function formatClockTime(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '--:--:--';
  const date = new Date(timestamp);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** One visible error line; the complete output remains in the disclosure body. */
export function toolFailureSummary(tool?: ToolCall): string {
  return tool?.status === 'failed' ? tool.result?.trim().split(/\r?\n/, 1)[0]?.slice(0, 160) ?? '' : '';
}

const THINKING_PREFERENCE_KEY = 'advisor:thinking_preference';

/** 读取用户持久化的 Think（深度思考）偏好。 */
export function getStoredThinkingPreference(): boolean {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return false;
    return window.localStorage.getItem(THINKING_PREFERENCE_KEY) === 'true';
  } catch {
    return false;
  }
}

/** 持久化用户 Think 偏好，确保刷新或重新登录后状态保持。 */
export function setStoredThinkingPreference(enabled: boolean): void {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(THINKING_PREFERENCE_KEY, enabled ? 'true' : 'false');
    }
  } catch {
    // Ignore localStorage errors (e.g. private browsing quota)
  }
}

const REASONING_EFFORT_PREFERENCE_KEY = 'advisor:reasoning_effort';
const REASONING_EFFORT_TIERS: readonly string[] = ['low', 'medium', 'high', 'xhigh', 'max'];

/** 读取用户持久化的推理程度档位；未设置或非法值返回 null 交由上层取默认。 */
export function getStoredReasoningEffortPreference(): ReasoningEffort | null {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return null;
    const value = window.localStorage.getItem(REASONING_EFFORT_PREFERENCE_KEY);
    return REASONING_EFFORT_TIERS.includes(value ?? '') ? (value as ReasoningEffort) : null;
  } catch {
    return null;
  }
}

/** 持久化用户推理程度档位，确保刷新或切换会话后状态保持。 */
export function setStoredReasoningEffortPreference(tier: ReasoningEffort): void {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(REASONING_EFFORT_PREFERENCE_KEY, tier);
    }
  } catch {
    // Ignore localStorage errors (e.g. private browsing quota)
  }
}

/** Semantic units for an i18n duration label; missing timing never becomes zero seconds. */
export function processDurationParts(ms: number | null | undefined): { hours: number; minutes: number; seconds: number; belowSecond: boolean } | null {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return null;
  const seconds = Math.floor(ms / 1000);
  return { hours: Math.floor(seconds / 3600), minutes: Math.floor(seconds / 60) % 60, seconds: seconds % 60, belowSecond: seconds < 1 };
}
