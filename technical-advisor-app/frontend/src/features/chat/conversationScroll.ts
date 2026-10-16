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

export interface ConversationAnchor { anchor: string; offset: number }

/** Capture the first visible block rather than a scroll height that will change on collapse. */
export function captureConversationAnchor(root: HTMLElement): ConversationAnchor | null {
  const top = root.getBoundingClientRect().top;
  for (const node of root.querySelectorAll<HTMLElement>('[data-anchor]')) {
    const rect = node.getBoundingClientRect();
    if (rect.height > 0 && rect.bottom > top) return { anchor: node.dataset.anchor!, offset: rect.top - top };
  }
  return null;
}

export function restoreConversationAnchor(root: HTMLElement, saved: ConversationAnchor | null): void {
  if (!saved) return;
  const node = [...root.querySelectorAll<HTMLElement>('[data-anchor]')].find(node => node.dataset.anchor === saved.anchor);
  if (!node || node.getBoundingClientRect().height === 0) return;
  root.scrollTop += node.getBoundingClientRect().top - root.getBoundingClientRect().top - saved.offset;
}
