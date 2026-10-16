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

/** Marks query words inside a retrieved passage. */
export function highlightPassage(passage: string, query: string): Array<{ text: string; hit: boolean }> {
  const terms = query
    .split(/\s+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
  if (terms.length === 0) {
    return [{ text: passage, hit: false }];
  }
  const pattern = new RegExp(terms.map(escapeRegExp).join('|'), 'gi');
  const parts: Array<{ text: string; hit: boolean }> = [];
  let cursor = 0;
  for (const match of passage.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > cursor) {
      parts.push({ text: passage.slice(cursor, start), hit: false });
    }
    parts.push({ text: match[0], hit: true });
    cursor = start + match[0].length;
  }
  if (cursor < passage.length) {
    parts.push({ text: passage.slice(cursor), hit: false });
  }
  return parts.length === 0 ? [{ text: passage, hit: false }] : parts;
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
