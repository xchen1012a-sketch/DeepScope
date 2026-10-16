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

/** Keep no-query calls compatible with existing selectors and callers. */
export function listQuery(path: string, query = ''): string {
  const value = query.trim();
  return value ? `${path}?${new URLSearchParams({ q: value })}` : path;
}

export type SelectionContext = { agentId?: string; draftId?: string };
export function selectionQuery(path: string, context: SelectionContext): string {
  return path + '?' + new URLSearchParams({ purpose: 'selection', ...context });
}

/** Selector pages reuse the management routes; these params scope them to one agent or draft. */
export function selectionListParams(context: SelectionContext = {}): ListFilters {
  return { purpose: 'selection', ...context };
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  defaultModel?: T | null;
  parsingCount?: number;
}

export type ListFilters = Record<string, string | undefined>;
export interface ListPageParams {
  page: number;
  size: number;
  q: string;
  [key: string]: string | number | undefined;
}

export function listPageQuery(path: string, params: ListPageParams): string {
  const values = new URLSearchParams({ page: String(params.page), size: String(params.size) });
  Object.entries(params).forEach(([key, value]) => {
    if (key !== 'page' && key !== 'size' && value !== undefined && value !== '') values.set(key, String(value));
  });
  return `${path}?${values}`;
}
