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
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { changePassword, revokeOtherSessions } from '@/api/account';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ConfirmActionDialog } from '@/components/ConfirmDeleteDialog';
import { RequestError, SettingRow } from './AccountShared';

export default function SecuritySection() {
  const { t } = useTranslation();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [confirm, setConfirm] = useState(false);
  const change = useMutation({ gcTime: 0, mutationFn: () => changePassword(current, next),
    onSuccess: () => { setCurrent(''); setNext(''); setRepeat(''); } });
  const revoke = useMutation({ mutationFn: revokeOtherSessions, onSuccess: () => setConfirm(false) });
  const mismatch = repeat.length > 0 && next !== repeat;
  const weak = next.length > 0 && (next.length < 8 || next.length > 256 || !next.trim());
  const ready = current.length > 0 && next.length >= 8 && next.length <= 256 && !!next.trim() && next === repeat;
  return <>
    <form onSubmit={event => { event.preventDefault(); if (ready && !change.isPending) change.mutate(); }}>
      <fieldset disabled={change.isPending}>
        <SettingRow label={<label htmlFor="current-password">{t('account.currentPassword')}</label>}>
          <Input id="current-password" className="max-w-xs" placeholder={t('account.currentPasswordPlaceholder')} type="password" autoComplete="current-password" value={current} maxLength={256} onChange={event => { setCurrent(event.target.value); change.reset(); }} />
        </SettingRow>
        <SettingRow label={<label htmlFor="new-password">{t('account.newPassword')}</label>}>
          <Input id="new-password" className="max-w-xs" placeholder={t('account.newPasswordPlaceholder')} type="password" autoComplete="new-password" aria-describedby="new-password-help" aria-invalid={weak}
            value={next} maxLength={256} onChange={event => { setNext(event.target.value); change.reset(); }} />
          <p id="new-password-help" className="mt-1 text-xs text-muted-foreground">{t('account.passwordLength')}</p>
        </SettingRow>
        <SettingRow label={<label htmlFor="repeat-password">{t('account.repeatPassword')}</label>}>
          <Input id="repeat-password" className="max-w-xs" placeholder={t('account.repeatPasswordPlaceholder')} type="password" autoComplete="new-password" value={repeat} maxLength={256} aria-invalid={mismatch} aria-describedby={mismatch ? 'password-mismatch' : undefined}
            onChange={event => { setRepeat(event.target.value); change.reset(); }} />
          {mismatch ? <p id="password-mismatch" role="alert" className="mt-1 text-xs text-red-700">{t('account.passwordMismatch')}</p> : null}
        </SettingRow>
        <div className="flex justify-end"><Button type="submit" disabled={!ready || change.isPending}>{t('account.changePassword')}</Button></div>
      </fieldset>
      <p className="mt-3 text-xs text-muted-foreground">{t('account.passwordHelp')}</p>
      {change.isError ? <RequestError error={change.error} /> : null}
      <p role="status" className="mt-2 text-xs text-muted-foreground">{change.isPending ? t('account.saving') : change.isSuccess ? t('account.passwordChanged') : ''}</p>
    </form>
    <SettingRow label={t('account.loginSessions')}>
      <Button variant="outline" onClick={() => setConfirm(true)} disabled={revoke.isPending}>{t('account.revokeOthers')}</Button>
      {revoke.isError ? <RequestError error={revoke.error} /> : null}
      {revoke.isSuccess ? <p role="status" className="mt-2 text-xs">{t('account.sessionsRevoked')}</p> : null}
    </SettingRow>
    <ConfirmActionDialog open={confirm} title={t('account.revokeOthers')} description={t('account.revokeConfirm')} confirmText={t('account.confirm')}
      isConfirming={revoke.isPending} onConfirm={() => revoke.mutate()} onClose={() => setConfirm(false)} />
  </>;
}
