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

import { describe, expect, it } from 'vitest';
import { highlightPassage } from './highlight';

describe('highlightPassage', () => {
  it('marks the words from the question', () => {
    const parts = highlightPassage('甲库独有的条款', '条款');
    expect(parts.some((part) => part.hit && part.text === '条款')).toBe(true);
    expect(parts.some((part) => !part.hit && part.text.includes('甲库'))).toBe(true);
  });
});
