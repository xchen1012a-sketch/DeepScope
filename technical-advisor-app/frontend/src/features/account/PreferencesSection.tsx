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
import { useTranslation } from 'react-i18next';
import { can } from '@/api/auth';
import { listAvailableAgents } from '@/api/agent';
import { api } from '@/lib/apiClient';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { AppSelect } from '@/components/ui/select';
import { LanguageSwitcher } from '@/app/LanguageSwitcher';
import { useAccountPreferences } from './useAccount';
import { AccountLoading, RequestError, SaveStatus, SettingRow } from './AccountShared';

interface Choice { endpointId: string; modelName: string; displayName?: string }
export default function PreferencesSection() {
  const { t } = useTranslation();
  const identity = useAccountIdentity();
  const preferences = useAccountPreferences();
  const [draft, setDraft] = useState<typeof preferences.data>();
  useEffect(() => { if (preferences.data && !preferences.saving) setDraft(preferences.data); }, [preferences.data, preferences.saving]);
  const chatAllowed = can('chat.use');
  const models = useQuery({ queryKey: ['account', identity?.userId, 'model-choices'], enabled: chatAllowed,
    retry: false, queryFn: async (): Promise<Choice[]> => {
      if (can('settings.manage')) return (await api.get<{ chat: Choice[] }>('/api/advisor/models')).chat;
      const current = await api.get<Choice>('/api/advisor/models/active');
      return current.endpointId ? [current] : [];
    } });
  const agents = useQuery({ queryKey: ['account', identity?.userId, 'agent-choices'], enabled: chatAllowed, queryFn: listAvailableAgents, retry: false });
  if (preferences.isPending) return <AccountLoading />;
  if (preferences.isError) return <RequestError error={preferences.error} retry={() => void preferences.refetch()} />;
  const value = draft ?? preferences.data;
  if (!value) return <AccountLoading />;
  const modelOptions = [{ value: '', label: t('account.systemModel') }, ...(models.data ?? []).map(model => ({ value: model.endpointId, label: model.displayName || model.modelName }))];
  const agentOptions = [{ value: '', label: t('account.freeAgent') }, ...(agents.data ?? []).map(agent => ({ value: agent.agentId, label: agent.name }))];
  if (value.defaultModelId && !modelOptions.some(option => option.value === value.defaultModelId)) modelOptions.push({ value: value.defaultModelId, label: t('account.unavailable') });
  if (value.defaultAgentId && !agentOptions.some(option => option.value === value.defaultAgentId)) agentOptions.push({ value: value.defaultAgentId, label: t('account.unavailable') });
  return <>
    <SettingRow label={t('account.thinking')} description={t('account.thinkingHelp')}>
      <button type="button" role="switch" aria-checked={value.thinkEnabled} aria-label={t('account.thinking')} disabled={preferences.saving}
        className="inline-flex min-h-11 items-center gap-2 rounded-md px-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
        onClick={() => { const next = { ...value, thinkEnabled: !value.thinkEnabled }; setDraft(next); preferences.save({ thinkEnabled: next.thinkEnabled }); }}>
        <span aria-hidden className={'flex h-6 w-10 items-center rounded-full p-1 transition-colors motion-reduce:transition-none ' + (value.thinkEnabled ? 'bg-primary' : 'bg-slate-300')}>
          <span className={'h-4 w-4 rounded-full bg-white transition-transform motion-reduce:transition-none ' + (value.thinkEnabled ? 'translate-x-4' : '')} />
        </span>{value.thinkEnabled ? t('account.on') : t('account.off')}
      </button>
    </SettingRow>
    <SettingRow label={t('account.defaultModel')}>
      <AppSelect aria-label={t('account.defaultModel')} value={value.defaultModelId} options={modelOptions} placeholder={t('account.systemModel')}
        disabled={preferences.saving || models.isPending || (!models.data?.length && !value.defaultModelId)}
        onChange={defaultModelId => { setDraft({ ...value, defaultModelId }); preferences.save({ defaultModelId }); }} />
      {models.isError ? <RequestError error={models.error} retry={() => void models.refetch()} /> : models.isSuccess && !models.data.length ? <p className="mt-1 text-xs text-muted-foreground">{t('account.noModels')}</p> : null}
    </SettingRow>
    <SettingRow label={t('account.defaultAgent')}>
      <AppSelect aria-label={t('account.defaultAgent')} value={value.defaultAgentId} options={agentOptions} placeholder={t('account.freeAgent')}
        disabled={preferences.saving || agents.isPending} onChange={defaultAgentId => { setDraft({ ...value, defaultAgentId }); preferences.save({ defaultAgentId }); }} />
      {agents.isError ? <RequestError error={agents.error} retry={() => void agents.refetch()} /> : null}
    </SettingRow>
    <SaveStatus pending={preferences.saving} success={preferences.saved} error={preferences.saveError} />
    <SettingRow label={t('account.language')} description={t('account.languageHelp')}><LanguageSwitcher /></SettingRow>
  </>;
}
