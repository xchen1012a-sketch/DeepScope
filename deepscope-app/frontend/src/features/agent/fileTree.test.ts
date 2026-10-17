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
import { buildFileTree, flattenTreeFiles } from './fileTree';

describe('buildFileTree', () => {
  it('nests files under their directories, directories first and siblings sorted by name', () => {
    const tree = buildFileTree(['subagents/a.md', 'AGENTS.md', 'knowledge/b.txt', 'subagents/c.md']);
    expect(tree.map((node) => node.name)).toEqual(['knowledge', 'subagents', 'AGENTS.md']);
    const subagents = tree.find((node) => node.name === 'subagents');
    expect(subagents?.kind).toBe('dir');
    expect(subagents?.path).toBe('subagents/');
    expect(subagents?.children.map((node) => node.name)).toEqual(['a.md', 'c.md']);
  });

  it('treats trailing-slash entries as empty directories', () => {
    const tree = buildFileTree(['notes/', 'notes/idea.md']);
    expect(tree).toHaveLength(1);
    expect(tree[0]).toMatchObject({ name: 'notes', path: 'notes/', kind: 'dir' });
    expect(tree[0].children.map((node) => node.path)).toEqual(['notes/idea.md']);
  });

  it('dedupes repeated paths and ignores blank or root-only entries', () => {
    const tree = buildFileTree(['AGENTS.md', 'AGENTS.md', '/AGENTS.md', ' ', '/']);
    expect(tree).toHaveLength(1);
    expect(tree[0]).toMatchObject({ name: 'AGENTS.md', path: 'AGENTS.md', kind: 'file' });
  });

  it('returns an empty tree for an empty path list', () => {
    expect(buildFileTree([])).toEqual([]);
  });
});

describe('flattenTreeFiles', () => {
  it('returns file paths in tree order without directories', () => {
    const tree = buildFileTree(['subagents/a.md', 'AGENTS.md', 'knowledge/b.txt']);
    expect(flattenTreeFiles(tree)).toEqual(['knowledge/b.txt', 'subagents/a.md', 'AGENTS.md']);
  });
});
