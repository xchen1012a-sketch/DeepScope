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

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, CircleAlert, Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ServerProviderSelect } from '@/components/ServerProviderSelect';
import { Page } from '@/components/Page';
import { usePagedList } from '@/components/usePagedList';
import { TableToolbar } from '@/components/TableToolbar';
import { DataTable, type DataTableColumn } from '@/components/DataTable';
import { ConfirmActionDialog, ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { activateModel, deleteModel, duplicateModel, modelError, modelKeys, modelLabel, testModel, type ModelEndpoint, type ModelKind, type ModelProviderPreset } from '@/api/model';
import { ModelEditorDrawer } from './components/ModelEditorDrawer';
import { can } from '@/api/auth';

export default function SettingsPage() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const key = 'modelSettings.manage.';
  const [kind, setKind] = useState<ModelKind>('chat');
  const query = usePagedList<ModelEndpoint>(modelKeys.all, '/api/advisor/models', { filters: { kind, presetId: '' } });
  const [selectedProvider, setSelectedProvider] = useState<ModelProviderPreset | null>(null);
  const [copying, setCopying] = useState<string | null>(null);
  const [draftSearch, setDraftSearch] = useState('');
  const search = query.params.q;
  const provider = query.params.presetId ?? '';
  const [editor, setEditor] = useState<{ model?: ModelEndpoint; kind: ModelKind } | null>(null);
  const [confirmation, setConfirmation] = useState<{ action: 'activate' | 'delete'; model: ModelEndpoint } | null>(null);
  const [notice, setNotice] = useState('');
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (noticeTimer.current) clearTimeout(noticeTimer.current); }, []);
  const showNotice = (message: string) => {
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    setNotice(message);
    noticeTimer.current = setTimeout(() => setNotice(''), 3500);
  };
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: modelKeys.all }),
      queryClient.invalidateQueries({ queryKey: ['advisor', 'model-current'] }),
      queryClient.invalidateQueries({ queryKey: ['advisor', 'session-model'] }),
    ]);
  };
  const action = useMutation({
    mutationFn: ({ action, model }: { action: 'activate' | 'delete'; model: ModelEndpoint }) => action === 'activate' ? activateModel(model.endpointId) : deleteModel(model.endpointId),
    onSuccess: async (_, input) => { await refresh(); setConfirmation(null); showNotice(t(key + (input.action === 'activate' ? 'defaultUpdated' : 'deleted'))); },
  });
  const test = useMutation({
    mutationFn: testModel,
    onSuccess: async result => { await refresh(); showNotice(result.ok ? t(key + 'testPassed', { ms: result.elapsedMs }) : result.message); },
    onError: error => showNotice(modelError(error, t(key + 'operationError'))),
  });
  const models = query.data?.items ?? [];
  async function copy(model: ModelEndpoint) {
    setCopying(model.endpointId);
    try {
      await duplicateModel(model.endpointId, t(key + 'copyName', { name: modelLabel(model) }));
      await queryClient.invalidateQueries({ queryKey: modelKeys.all, refetchType: 'none' });
      query.setPage(Math.max(1, Math.ceil(((query.data?.total ?? 0) + 1) / query.params.size)));
      showNotice(t(key + 'copied'));
    } catch {
      showNotice(t(key + 'copyError'));
    } finally {
      setCopying(null);
    }
  }
  function confirm(model: ModelEndpoint, actionType: 'activate' | 'delete') { action.reset(); setConfirmation({ action: actionType, model }); }
  const columns: DataTableColumn<ModelEndpoint>[] = [
    { id: 'model', header: t(key + 'modelColumn'), headerClassName: 'pl-6', cellClassName: 'pl-6', cell: model => <div className="min-w-44">
      <div className="flex items-center gap-2"><span className="font-semibold text-slate-900">{modelLabel(model)}</span>{model.current ? <Badge tone="info">{t(key + 'defaultBadge')}</Badge> : null}</div>
      <p className="mt-1 font-mono text-xs text-slate-400">{model.modelName}</p>
    </div> },
    { id: 'provider', header: t('modelSettings.providerLabel'), cell: model => <span className="text-sm text-slate-600">{model.providerName || t(`modelSettings.provider.${model.provider}`)}</span> },
    { id: 'address', header: t(key + 'addressColumn'), cell: model => <span title={model.baseUrl} className="block max-w-64 truncate font-mono text-xs text-slate-500">{model.baseUrl || '—'}</span> },
    { id: 'key', header: t(key + 'keyColumn'), cell: model => <span className="whitespace-nowrap text-xs text-slate-500">{t(key + (model.secretMask ? 'keyConfigured' : 'keyMissing'))}</span> },
    ...(kind === 'embedding' ? [{ id: 'dimensions', header: t(key + 'dimensionsColumn'), headerClassName: 'whitespace-nowrap', cellClassName: 'whitespace-nowrap', cell: (model: ModelEndpoint) => <span className="text-xs tabular-nums text-slate-500">{model.dimensions ?? '—'}</span> }] : []),
    { id: 'test', header: t(key + 'lastTest'), cell: model => <div className="min-w-28 space-y-1">
      {model.lastTestStatus ? <span className={`flex items-center gap-1.5 text-xs ${model.lastTestStatus === 'passed' ? 'text-emerald-700' : 'text-red-600'}`}>
        {model.lastTestStatus === 'passed' ? <CheckCircle2 className="h-3.5 w-3.5" /> : <CircleAlert className="h-3.5 w-3.5" />}{t(key + (model.lastTestStatus === 'passed' ? 'passed' : 'failed'))}
        {model.lastTestStatus === 'passed' && model.lastTestElapsedMs != null ? <span className="text-slate-400">{model.lastTestElapsedMs} ms</span> : null}
      </span> : <span className="text-xs text-slate-400">{t(key + 'notTested')}</span>}
      {model.lastTestAt ? <p className="text-xs text-slate-400">{new Date(model.lastTestAt).toLocaleString(i18n.language, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</p> : null}
      {model.lastTestStatus === 'failed' && model.lastTestMessage ? <p className="max-w-60 text-xs text-red-600">{model.lastTestMessage}</p> : null}
    </div> },

  ];
  return <Page>
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div role="tablist" aria-label={t(key + 'modelType')} className="inline-flex gap-1 rounded-xl bg-muted p-1">
        {(['chat', 'embedding'] as const).map(value => <button key={value} role="tab" type="button" aria-selected={kind === value}
          onClick={() => { setKind(value); query.reset({ kind: value, presetId: '' }); setSelectedProvider(null); setDraftSearch(''); setNotice(''); }}
          className={`rounded-lg px-4 py-2 text-sm font-medium ${kind === value ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-800'}`}>{t(value === 'chat' ? 'modelSettings.chatTitle' : 'modelSettings.embeddingTitle')}</button>)}
      </div>
      {can('model.create') ? <Button onClick={() => setEditor({ kind })}><Plus className="h-4 w-4" />{t(key + 'connect')}</Button> : null}
    </div>
    <TableToolbar search={draftSearch} onSearchChange={setDraftSearch} onSearch={() => query.submit(draftSearch)} placeholder={t(key + 'searchPlaceholder')}
      filters={<div className="w-52"><ServerProviderSelect id="model-provider-filter" kind={kind} label={t(key + 'filterProvider')} placeholder={t(key + 'allProviders')} allowClear
        value={selectedProvider?.id || ''} displayValue={selectedProvider?.name}
        onChange={preset => { setSelectedProvider(preset); query.setFilters({ kind, presetId: preset?.id || '' }); }} /></div>}
    />
    <DataTable columns={columns}
        actions={model => [
          { id: 'configure', label: t(key + 'configure'), hidden: !can('model.edit'), onClick: () => setEditor({ model, kind }) },
          { id: 'copy', label: t(key + 'copyConfig'), hidden: !can('model.copy'), disabled: copying !== null, loading: copying === model.endpointId, onClick: () => void copy(model) },
          { id: 'test', label: t(key + 'test'), hidden: !can('model.test'), overflow: true, loading: test.isPending && test.variables === model.endpointId, disabled: test.isPending || action.isPending, onClick: () => { setNotice(''); test.mutate(model.endpointId); } },
          { id: 'default', label: t(key + 'setDefault'), overflow: true, hidden: model.current || !can('model.edit'), disabled: action.isPending, onClick: () => confirm(model, 'activate') },
          { id: 'delete', label: t('modelSettings.delete'), hidden: !can('model.delete'), overflow: true, tone: 'danger', disabled: model.current || action.isPending, onClick: () => confirm(model, 'delete') },
        ]}
        data={models} keyExtractor={model => model.endpointId} isLoading={query.isPending} error={query.isError ? t(key + 'loadError') : undefined} onRetry={() => { void query.refetch(); }}
        emptyTitle={t(key + (search || provider ? 'noResults' : kind === 'chat' ? 'emptyChat' : 'emptyEmbedding'))}
        emptyAction={search || provider ? <Button variant="outline" onClick={() => { setDraftSearch(''); setSelectedProvider(null); query.reset({ kind, presetId: '' }); }}>{t(key + 'clearFilters')}</Button> : can('model.create') ? <Button onClick={() => setEditor({ kind })}>{t(key + 'connect')}</Button> : undefined}
        page={query.data?.page ?? query.params.page} pageSize={query.params.size} total={query.data?.total ?? 0} onPageChange={query.setPage} onPageSizeChange={query.setPageSize} />
    {notice ? <div role="status" aria-label={t(key + 'notification')} className="fixed right-5 top-5 z-[80] max-w-sm rounded-lg border border-emerald-200 bg-white px-4 py-3 text-sm text-emerald-800 shadow-lg">{notice}</div> : null}
    {editor ? <ModelEditorDrawer key={editor.model?.endpointId || editor.kind} {...editor} onClose={() => setEditor(null)} onSaved={() => { void refresh(); setNotice(t(key + 'saved')); }} /> : null}
    <ConfirmActionDialog open={confirmation?.action === 'activate'} title={t(key + (kind === 'chat' ? 'defaultConfirmTitle' : 'embeddingConfirmTitle'))}
      description={<><p className="font-medium text-slate-900">{confirmation ? modelLabel(confirmation.model) : ''}</p><p className="mt-2">{t(key + (kind === 'chat' ? 'defaultImpact' : 'embeddingImpact'))}</p></>}
      confirmText={t(key + 'setDefault')} isConfirming={action.isPending} onConfirm={() => { if (confirmation) action.mutate(confirmation); }} onClose={() => setConfirmation(null)}>
      {action.isError ? <p role="alert" className="text-sm text-red-600">{modelError(action.error, t(key + 'operationError'))}</p> : null}
    </ConfirmActionDialog>
    <ConfirmDeleteDialog open={confirmation?.action === 'delete'} title={t(key + 'deleteTitle')} description={t(key + 'deleteHint', { name: confirmation ? modelLabel(confirmation.model) : '' })}
      confirmText={t('modelSettings.delete')} isDeleting={action.isPending} onConfirm={() => { if (confirmation) action.mutate(confirmation); }} onClose={() => setConfirmation(null)}>
      {action.isError ? <p role="alert" className="text-sm text-red-600">{modelError(action.error, t(key + 'operationError'))}</p> : null}
    </ConfirmDeleteDialog>
  </Page>;
}
