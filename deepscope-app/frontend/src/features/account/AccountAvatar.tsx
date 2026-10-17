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

import { useAvatar } from '@/hooks/useAvatar';
import type { MeResponse } from '@/api/auth';
export function AccountAvatar({ account, className = 'h-12 w-12' }: { account?: Pick<MeResponse, 'userId' | 'username' | 'nickname' | 'avatarUrl' | 'avatarUpdatedAt'>; className?: string }) {
  const { url } = useAvatar(account);
  const initial = Array.from(account?.nickname?.trim() || account?.username || '?')[0].toUpperCase();
  return url ? <img src={url} alt="" className={className + ' shrink-0 rounded-full object-cover'} />
    : <span aria-hidden className={className + ' inline-flex shrink-0 items-center justify-center rounded-full bg-accent text-sm font-medium text-accent-foreground'}>{initial}</span>;
}
