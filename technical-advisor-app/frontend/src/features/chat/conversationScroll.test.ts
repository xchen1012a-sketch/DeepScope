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

// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { captureConversationAnchor, restoreConversationAnchor } from './conversationScroll';

describe('conversation reading position', () => {
  it('keeps the visible paragraph at the same viewport offset when a preceding process collapses', () => {
    const root = document.createElement('div');
    root.innerHTML = '<div data-anchor="old"></div><div data-anchor="reading"></div>';
    const visible = root.lastElementChild as HTMLElement;
    root.getBoundingClientRect = () => ({ top: 100 } as DOMRect);
    (root.firstElementChild as HTMLElement).getBoundingClientRect = () => ({ top: 0, bottom: 50, height: 50 } as DOMRect);
    visible.getBoundingClientRect = () => ({ top: 110, bottom: 210, height: 100 } as DOMRect);
    root.scrollTop = 300;
    const anchor = captureConversationAnchor(root);
    expect(anchor?.anchor).toBe('reading');
    visible.getBoundingClientRect = () => ({ top: -90, bottom: 10, height: 100 } as DOMRect);
    restoreConversationAnchor(root, anchor);
    expect(root.scrollTop).toBe(100);
  });
  it('does not select hidden anchors or invent a target for empty content', () => {
    const root = document.createElement('div');
    root.innerHTML = '<div data-anchor="hidden"></div>';
    expect(captureConversationAnchor(root)).toBeNull();
    expect(() => restoreConversationAnchor(root, { anchor: 'missing', offset: 5 })).not.toThrow();
  });
});
