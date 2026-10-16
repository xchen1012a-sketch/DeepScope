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

import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { accountKeys, getPreferences, updatePreferences, type PreferenceUpdate } from '@/api/account';
import { getToken, me } from '@/api/auth';
import { ApiError } from '@/lib/apiClient';
import { getAccountIdentity, useAccountIdentity } from '@/lib/accountIdentity';

export function useAccountProfile() {
  const identity = useAccountIdentity();
  return useQuery({ queryKey: accountKeys.profile(identity?.userId ?? ''), queryFn: me, enabled: !!identity,
    staleTime: 0, refetchOnWindowFocus: true, retry: false });
}
export function useAccountPreferences() {
  const identity = useAccountIdentity();
  const key = accountKeys.preferences(identity?.userId ?? '');
  const client = useQueryClient();
  const query = useQuery({ queryKey: key, enabled: !!identity, retry: false, queryFn: async ({ signal }) => {
    const expectedUserId = identity?.userId;
    const expectedToken = getToken();
    const stillSameAccount = () => getToken() === expectedToken && getAccountIdentity(expectedToken)?.userId === expectedUserId;
    const saved = await getPreferences(signal);
    if (!stillSameAccount()) throw new DOMException('Account changed', 'AbortError');
    let local: string | null = null;
    try { local = localStorage.getItem('advisor:thinking_preference'); } catch { /* Browser storage is optional. */ }
    if (saved.initialized || (local !== 'true' && local !== 'false')) return saved;
    try {
      const migrated = await updatePreferences({ thinkEnabled: local === 'true' }, true, signal);
      if (!stillSameAccount()) throw new DOMException('Account changed', 'AbortError');
      return migrated;
    }
    catch (error) {
      if (error instanceof ApiError && error.status === 412 && stillSameAccount()) return getPreferences(signal);
      throw error;
    }
  } });
  const mutation = useMutation({ mutationKey: [...key, 'save'],
    mutationFn: (value: PreferenceUpdate) => updatePreferences(value),
    onSuccess: value => client.setQueryData(key, value),
  });
  const { reset } = mutation;
  useEffect(() => {
    if (!mutation.isSuccess) return;
    const timer = window.setTimeout(reset, 2000);
    return () => window.clearTimeout(timer);
  }, [mutation.isSuccess, reset]);
  return { ...query, save: mutation.mutate, saving: mutation.isPending,
    saveError: mutation.error, saved: mutation.isSuccess };
}
