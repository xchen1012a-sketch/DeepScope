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

/** 工作区文件树节点：目录递归展开，文件为叶子。 */
export interface FileTreeNode {
  name: string;
  path: string;
  kind: 'dir' | 'file';
  children: FileTreeNode[];
}

function newNode(name: string, path: string, kind: 'dir' | 'file'): FileTreeNode {
  return { name, path, kind, children: [] };
}

/**
 * 把 tree 接口返回的扁平路径清单组成一棵树：目录在前、同级按名称排序；
 * 以 / 结尾的路径视为空目录；去重后忽略空路径。
 */
export function buildFileTree(paths: string[]): FileTreeNode[] {
  const root: FileTreeNode = newNode('', '', 'dir');
  for (const raw of [...new Set(paths)]) {
    const trimmed = raw.trim();
    if (!trimmed || trimmed === '/') continue;
    const isDir = trimmed.endsWith('/');
    const segments = trimmed.split('/').filter((segment) => segment.length > 0);
    if (segments.length === 0) continue;
    let node = root;
    segments.forEach((segment, index) => {
      const leaf = index === segments.length - 1;
      const path = `${segments.slice(0, index + 1).join('/')}${leaf && !isDir ? '' : '/'}`;
      let child = node.children.find((entry) => entry.name === segment && entry.path === path);
      if (!child) {
        child = newNode(segment, path, leaf && !isDir ? 'file' : 'dir');
        node.children.push(child);
      }
      node = child;
    });
  }
  const sortNodes = (nodes: FileTreeNode[]): FileTreeNode[] => {
    nodes.sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === 'dir' ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    nodes.forEach((node) => sortNodes(node.children));
    return nodes;
  };
  return sortNodes(root.children);
}

/** 展平树为文件路径清单（按树顺序，不含目录）。 */
export function flattenTreeFiles(nodes: FileTreeNode[]): string[] {
  return nodes.flatMap((node) =>
    node.kind === 'file' ? [node.path] : flattenTreeFiles(node.children),
  );
}
