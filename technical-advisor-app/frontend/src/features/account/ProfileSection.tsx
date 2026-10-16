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

import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { accountKeys, deleteAvatar, updateNickname, uploadAvatar } from '@/api/account';
import { useAccountProfile } from './useAccount';
import { AccountAvatar } from './AccountAvatar';
import { AccountLoading, RequestError, SaveStatus, SettingRow, useTransientSuccess } from './AccountShared';
import { useAvatar } from '@/hooks/useAvatar';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { ConfirmActionDialog } from '@/components/ConfirmDeleteDialog';
import { Link, useLocation } from 'react-router-dom';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Camera, MoreHorizontal } from 'lucide-react';

export default function ProfileSection() {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const profile = useAccountProfile();
  const client = useQueryClient();
  const [draft, setDraft] = useState<string | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [fileError, setFileError] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const save = useMutation({ mutationFn: updateNickname, onSuccess: value => {
    client.setQueryData(accountKeys.profile(value.userId), value); setDraft(null);
  } });
  const avatar = useMutation({ mutationFn: async (file: File | null) => file ? uploadAvatar(file) : deleteAvatar(),
    onSuccess: async () => {
      setRemoveOpen(false);
      await client.invalidateQueries({ queryKey: accountKeys.profile(profile.data?.userId ?? '') });
    } });
  useTransientSuccess(save);
  useTransientSuccess(avatar);
  const preview = useAvatar(profile.data);
  if (profile.isPending) return <AccountLoading />;
  if (profile.isError) return <RequestError error={profile.error} retry={() => void profile.refetch()} />;
  const account = profile.data;
  const nickname = draft ?? account.nickname ?? '';
  const saveNickname = () => {
    const value = nickname.trim();
    if (save.isPending || value === (account.nickname ?? '')) return;
    save.mutate(value);
  };
  const chooseFile = (file?: File) => {
    if (!file) return;
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024 || file.size === 0) {
      setFileError(true); return;
    }
    setFileError(false); avatar.mutate(file);
  };
  return <>
    <div className="mb-2 border-b border-slate-100 pb-6 pt-1">
      <div className="flex items-center gap-4">
        <button type="button" aria-label={t('account.changeAvatar')} title={t('account.changeAvatar')} disabled={avatar.isPending} onClick={() => fileInput.current?.click()} className="group relative shrink-0 rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-50">
          <AccountAvatar account={account} className="h-16 w-16 ring-2 ring-white" />
          <span className="absolute inset-0 flex items-center justify-center rounded-full bg-black/30 text-white opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"><Camera aria-hidden className="h-5 w-5" /></span>
        </button>
        <div className="min-w-0 flex-1"><p className="break-words text-lg font-medium text-slate-900">{account.nickname || account.username}</p><p className="mt-0.5 text-xs text-slate-500">@{account.username}</p><p className="mt-2 text-xs text-indigo-700">{account.roleNames?.join(' / ') || t('account.none')}</p></div>
        {account.avatarUrl ? <DropdownMenu.Root><DropdownMenu.Trigger asChild><Button variant="ghost" size="icon" disabled={avatar.isPending} aria-label={t('account.avatarActions')}><MoreHorizontal className="h-4 w-4" /></Button></DropdownMenu.Trigger><DropdownMenu.Portal><DropdownMenu.Content align="end" className="z-[70] rounded-lg border border-border bg-white p-1 shadow-lg"><DropdownMenu.Item onSelect={() => setRemoveOpen(true)} className="cursor-default rounded-md px-3 py-2 text-xs text-destructive outline-none data-[highlighted]:bg-red-50">{t('account.removeAvatar')}</DropdownMenu.Item></DropdownMenu.Content></DropdownMenu.Portal></DropdownMenu.Root> : null}
        <input ref={fileInput} id="account-avatar-file" type="file" className="sr-only" aria-label={t('account.uploadAvatar')} accept="image/png,image/jpeg,image/webp" disabled={avatar.isPending}
          onChange={event => { chooseFile(event.target.files?.[0]); event.target.value = ''; }} />
      </div>
      {fileError ? <p role="alert" className="mt-2 text-xs text-red-700">{t('account.errors.invalid_avatar')}</p> : null}
      {preview.isError ? <RequestError retry={() => void preview.retry()} /> : null}
      <SaveStatus pending={avatar.isPending} success={avatar.isSuccess} error={avatar.error} />
    </div>
    <SettingRow label={<label htmlFor="account-nickname">{t('account.nickname')}</label>}>
      <div className="flex max-w-[360px] items-center gap-2">
      <Input id="account-nickname" className="min-w-0 flex-1" value={nickname} maxLength={40} disabled={save.isPending}
        onChange={event => { setDraft(event.target.value); save.reset(); }}
        onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); saveNickname(); } }} />
      <Button size="sm" variant="outline" disabled={save.isPending || nickname.trim() === (account.nickname ?? '')} onClick={saveNickname}>{t('account.save')}</Button>
      </div>
      <SaveStatus pending={save.isPending} success={save.isSuccess} error={save.error} />
    </SettingRow>
    <SettingRow label={t('account.username')}>{account.username}</SettingRow>
    <SettingRow label={t('account.createdAt')}>{account.createdAt ? new Date(account.createdAt).toLocaleString(i18n.language) : t('account.none')}</SettingRow>
    <div className="flex flex-wrap gap-2 pt-5">
      <Button variant="outline" size="sm" asChild><Link replace state={location.state} to="/account/security">{t('account.changePassword')}</Link></Button>
      <Button variant="outline" size="sm" asChild><Link replace state={location.state} to="/account/preferences">{t('account.sections.preferences')}</Link></Button>
    </div>
    <ConfirmActionDialog open={removeOpen} title={t('account.removeAvatar')} description={t('account.removeAvatarConfirm')}
      confirmText={t('account.confirm')} isConfirming={avatar.isPending} onClose={() => setRemoveOpen(false)} onConfirm={() => avatar.mutate(null)} />
  </>;
}
