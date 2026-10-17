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

import { useState } from 'react';
import { useQuery, type QueryKey, type UseQueryOptions } from '@tanstack/react-query';
import { api } from '@/lib/apiClient';
import { listPageQuery, type ListFilters, type ListPageParams, type PageResult } from '@/api/listQuery';

/** Submitted list conditions have their own cache; selectors keep the full resource cache. */
export function usePagedList<T>(resourceKey: QueryKey, path: string, options: { filters?: ListFilters; extra?: ListFilters; enabled?: boolean; refetchInterval?: UseQueryOptions<PageResult<T>>['refetchInterval'] } = {}) {
  const [state, setState] = useState(() => ({ page: 1, size: 10, q: '', filters: options.filters ?? {}, revision: 0 }));
  const params: ListPageParams = { page: state.page, size: state.size, q: state.q, ...state.filters, ...options.extra };
  const result = useQuery({
    queryKey: [...resourceKey, 'page', path, params, state.revision],
    queryFn: () => api.get<PageResult<T>>(listPageQuery(path, params)),
    enabled: options.enabled ?? true,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchInterval: options.refetchInterval,
  });
  return {
    ...result,
    params,
    submit: (q: string, filters?: ListFilters) => setState(current => ({ ...current, q: q.trim(), filters: filters ? { ...current.filters, ...filters } : current.filters, page: 1, revision: current.revision + 1 })),
    setPage: (page: number) => setState(current => ({ ...current, page: Math.max(1, Math.floor(page)), revision: current.revision + 1 })),
    setPageSize: (size: number) => setState(current => ({ ...current, size: Math.max(1, Math.min(100, Math.floor(size))), page: 1 })),
    setFilters: (filters: ListFilters) => setState(current => ({ ...current, filters: { ...current.filters, ...filters }, page: 1, revision: current.revision + 1 })),
    reset: (filters: ListFilters = {}) => setState(current => ({ ...current, q: '', filters, page: 1, revision: current.revision + 1 })),
  };
}
