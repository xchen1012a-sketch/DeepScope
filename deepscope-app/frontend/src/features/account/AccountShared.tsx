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

import { useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { accountErrorKey } from '@/api/account';

export function SettingRow({ label, children, description }: { label: ReactNode; children: ReactNode; description?: ReactNode }) {
  return <div className="grid gap-3 border-b border-slate-100 py-4 last:border-b-0 sm:grid-cols-[minmax(8rem,0.55fr)_minmax(0,1.45fr)] sm:gap-5">
    <div className="min-w-0"><div className="text-sm font-medium">{label}</div>
      {description ? <p className="mt-1 text-xs text-muted-foreground">{description}</p> : null}</div>
    <div className="min-w-0 w-full sm:max-w-[560px]">{children}</div>
  </div>;
}
export function RequestError({ error, retry }: { error?: unknown; retry?: () => void }) {
  const { t } = useTranslation();
  return <div role="alert" className="space-y-2 text-sm text-red-700"><p>{t(accountErrorKey(error))}</p>
    {retry ? <Button variant="outline" size="sm" onClick={retry}>{t('account.retry')}</Button> : null}</div>;
}
export function AccountLoading() { const { t } = useTranslation(); return <div role="status" aria-label={t('shell.loading')} className="space-y-5"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-2/3" /></div>; }
export function SaveStatus({ pending, success, error }: { pending: boolean; success: boolean; error: unknown }) {
  const { t } = useTranslation();
  if (error) return <RequestError error={error} />;
  return pending || success ? <p role="status" className="mt-2 text-xs text-muted-foreground">{pending ? t('account.saving') : t('account.saved')}</p> : null;
}

export function useTransientSuccess({ isSuccess, reset }: { isSuccess: boolean; reset: () => void }) {
  useEffect(() => {
    if (!isSuccess) return;
    const timer = window.setTimeout(reset, 2000);
    return () => window.clearTimeout(timer);
  }, [isSuccess, reset]);
}
