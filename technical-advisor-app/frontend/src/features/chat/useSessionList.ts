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
import { useQuery } from '@tanstack/react-query';
import { advisorKeys, listSessions } from '@/api/advisor';

/** Header and sidebar observe one title snapshot and share TanStack Query requests. */
export function useSessionList(enabled = true) {
  return useQuery({
    queryKey: advisorKeys.sessions,
    queryFn: listSessions,
    enabled,
    refetchInterval: query => query.state.data?.some(session => session.status === 'running'
      || !session.title?.trim() && Date.now() - session.updatedAt < 120_000) ? 1000 : false,
    refetchOnWindowFocus: true,
    retry: false,
  });
}
