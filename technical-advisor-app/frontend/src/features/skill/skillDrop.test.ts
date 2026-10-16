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
import { fileFromClipboard, firstFile } from './skillDrop';

describe('skill drop', () => {
  it('keeps the first dropped file', () => {
    const file = new File(['---'], 'pack.zip');
    expect(firstFile([file, new File(['x'], 'other.md')])).toBe(file);
    expect(firstFile(null)).toBeNull();
  });

  it('turns pasted text into a markdown file', () => {
    const data = {
      files: [] as unknown as FileList,
      getData: (type: string) => (type === 'text/plain' ? '---\nname: 审查\n' : ''),
    } as unknown as DataTransfer;
    const file = fileFromClipboard(data);
    expect(file?.name).toBe('skill.md');
    expect(file?.type).toBe('text/markdown');
  });

  it('prefers a pasted file over text', () => {
    const packed = new File(['zip'], 'pack.zip');
    const data = {
      files: [packed] as unknown as FileList,
      getData: () => 'ignore',
    } as unknown as DataTransfer;
    expect(fileFromClipboard(data)).toBe(packed);
  });
});
