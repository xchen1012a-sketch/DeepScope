/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
const PREFIX = 'deepscope:tab-draft:';
export function draftKey(userId: string | undefined, scope: string): string | null {
  return userId ? PREFIX + encodeURIComponent(userId) + ':' + scope : null;
}
export function readDraft<T>(key: string | null, initial: T): T {
  if (!key) return initial;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return initial;
    const saved: unknown = JSON.parse(raw);
    if (typeof initial === 'string') return typeof saved === 'string' ? saved as T : initial;
    if (!saved || typeof saved !== 'object' || Array.isArray(saved) || !initial || typeof initial !== 'object') return initial;
    const record = saved as Record<string, unknown>;
    return Object.entries(initial).every(([field, value]) => value === undefined || typeof record[field] === typeof value)
      ? saved as T : initial;
  } catch { return initial; }
}
export function writeDraft<T>(key: string | null, value: T): void {
  if (!key) return;
  try { sessionStorage.setItem(key, JSON.stringify(value)); } catch { /* Keep input usable if storage is unavailable. */ }
}
export function removeDraft(key: string | null): void {
  if (!key) return;
  try { sessionStorage.removeItem(key); } catch { /* Storage is optional. */ }
}
export function clearTabDrafts(): void {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index);
      if (key?.startsWith(PREFIX)) sessionStorage.removeItem(key);
    }
  } catch { /* Storage is optional. */ }
}
