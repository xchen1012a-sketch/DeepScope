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

import type { EventEntry } from '@/api/advisor';

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

const normalized = (value: string) => value.replace(/\s+/g, ' ').trim();
const contentKeys = (value: string) => [value, ...value.split(/\r?\n\s*\r?\n|\r?\n(?=\s*(?:[-*+]|\d+[.)])\s)/)]
  .map(text => normalized(text).replace(/^(?:[-*+]|\d+[.)])\s+/, ''));

/** Keep old conclusions and actual links; titles and archive metadata alone are not answers. */
export function legacyDeliverableText(payload: Record<string, unknown>, replies: string[]): string {
  const existing = new Set(replies.flatMap(contentKeys));
  const take = (value: unknown): string => {
    if (typeof value !== 'string' || !value.trim()) return '';
    const key = contentKeys(value)[0];
    if (existing.has(key)) return '';
    existing.add(key);
    return value.trim();
  };
  const sections = list(payload.sections).flatMap(raw => {
    const section = record(raw);
    if (!section) return [];
    const body = take(section.body);
    const items = list(section.items).flatMap(rawItem => {
      const item = record(rawItem);
      return item ? [item.text, item.conflict, item.recommendation].map(take).filter(Boolean).map(text => `- ${text}`) : [];
    });
    const content = [body, ...items].filter(Boolean).join('\n\n');
    if (!content) return [];
    return [typeof section.title === 'string' && section.title.trim() ? `## ${section.title.trim()}\n\n${content}` : content];
  });
  const evidence = list(payload.evidence).flatMap(raw => {
    const fact = record(raw);
    return fact ? [fact.fact, fact.source].map(take).filter(Boolean).map(text => `- ${text}`) : [];
  });
  const followups = list(payload.suggestedFollowUps).map(take).filter(Boolean).map(text => `- ${text}`);
  return [...sections, ...evidence, ...followups].join('\n\n');
}

/** Normalize both replay representations before the shared block/timeline projection. */
export function normalizeLegacyDeliverables(events: EventEntry[]): EventEntry[] {
  const repliesByTurn = new Map<number, string[]>();
  for (const event of events) {
    if (event.kind !== 'advisor_message') continue;
    const replies = repliesByTurn.get(event.turn) ?? [];
    if (Array.isArray(event.payload.parts)) {
      for (const raw of event.payload.parts) {
        const part = record(raw);
        if (part?.kind === 'text' && typeof part.text === 'string') replies.push(part.text);
      }
    } else if (typeof event.payload.text === 'string') replies.push(event.payload.text);
    repliesByTurn.set(event.turn, replies);
  }
  const convertedById = new Map<string, string>();
  const conclusion = (id: string, turn: number, payload: Record<string, unknown>) => {
    if (convertedById.has(id)) return convertedById.get(id)!;
    const replies = repliesByTurn.get(turn) ?? [];
    const text = legacyDeliverableText(payload, replies);
    if (text) replies.push(text);
    repliesByTurn.set(turn, replies);
    convertedById.set(id, text);
    return text;
  };
  // Cache both representations of one event before consuming later report rows.
  for (const event of events) if (event.kind === 'deliverable') conclusion(`seq:${event.seq}`, event.turn, event.payload);
  const convert = (event: EventEntry): EventEntry | null => {
    if (event.kind !== 'deliverable') return event;
    const text = conclusion(`seq:${event.seq}`, event.turn, event.payload);
    return text ? { ...event, kind: 'advisor_message', payload: { text, legacyDeliverable: true } } : null;
  };
  return events.flatMap(event => {
    const converted = convert(event);
    if (!converted) return [];
    if (event.kind !== 'advisor_message' || !Array.isArray(event.payload.parts)) return [converted];
    return [{ ...event, payload: { ...event.payload, parts: event.payload.parts.flatMap(raw => {
      const part = record(raw);
      const embedded = part?.kind === 'event' ? record(part.event) : null;
      if (!embedded || embedded.kind !== 'deliverable') return [raw];
      const text = conclusion(typeof embedded.seq === 'number' ? `seq:${embedded.seq}` : `part:${part?.id}`, event.turn, record(embedded.payload) ?? {});
      return text ? [{ ...part, kind: 'text', text }] : [];
    }) } }];
  });
}
