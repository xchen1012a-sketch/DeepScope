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

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getAvatar } from '@/api/account';

export function useAvatar(profile?: { userId: string; avatarUrl?: string | null; avatarUpdatedAt?: number }) {
  const userId = profile?.userId ?? '';
  const version = profile?.avatarUpdatedAt ?? 0;
  const available = Boolean(userId && profile?.avatarUrl && version);
  const avatar = useQuery({
    queryKey: ['account', userId, 'avatar', version],
    queryFn: ({ signal }) => getAvatar(version, signal),
    enabled: available, retry: false, gcTime: 0, staleTime: Infinity,
  });
  const [url, setUrl] = useState<{ owner: string; version: number; source: Blob; value: string }>();
  useEffect(() => {
    if (!available || !avatar.data) return;
    const value = URL.createObjectURL(avatar.data);
    setUrl({ owner: userId, version, source: avatar.data, value });
    return () => URL.revokeObjectURL(value);
  }, [available, avatar.data, userId, version]);
  return { url: available && url?.owner === userId && url.version === version && url.source === avatar.data ? url.value : undefined,
    isLoading: available && avatar.isPending, isError: available && avatar.isError, retry: avatar.refetch };
}
