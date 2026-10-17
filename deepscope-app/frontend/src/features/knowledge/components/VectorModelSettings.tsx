/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { AppSelect } from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { ConfirmActionDialog } from '@/components/ConfirmDeleteDialog';
import { api, apiFetch, ApiError } from '@/lib/apiClient';
import type { LibraryDetail } from '../types';

interface Choice { endpointId: string; name: string; dimensions: number; systemDefault: boolean }
interface Reindexed { affectedDocuments: number }

export function VectorModelSettings({ library, onChanged, onReindexed }: {
  library: LibraryDetail; onChanged: () => void; onReindexed?: () => void;
}) {
  const { t } = useTranslation();
  const text = (key: string) => t(`knowledge.rebind.${key}`);
  const [baseline, setBaseline] = useState(library.embeddingEndpointId ?? '');
  const [selected, setSelected] = useState(baseline);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const base = `/api/advisor/knowledge/${encodeURIComponent(library.libraryId)}`;
  const choices = useQuery({ queryKey: ['advisor', 'knowledge', library.libraryId, 'embedding-models'],
    queryFn: async () => {
      const result = await api.get<Choice[]>(`${base}/embedding-models`);
      if (!Array.isArray(result)) throw new Error('Invalid model options response');
      return result;
    }, enabled: library.canEdit, retry: false });
  const change = useMutation({
    mutationFn: (id: string) => apiFetch<Reindexed>(`${base}/reindex`, { method: 'POST', body: JSON.stringify({ embeddingEndpointId: id }) }),
    onSuccess: (result, id) => {
      setBaseline(id); setSelected(id); setConfirming(false); setError('');
      setNotice(text(result.affectedDocuments > 0 ? 'notice' : 'saved'));
      onChanged();
      if (result.affectedDocuments > 0) onReindexed?.();
    },
    onError: error => { setConfirming(false); setError(text(error instanceof ApiError && error.status === 409 ? 'busy' : 'failed')); },
  });
  const busy = change.isPending || (library.parsingCount ?? 0) > 0;
  const valid = selected !== baseline && !!choices.data?.some(choice => choice.endpointId === selected);
  const count = library.documentCount ?? library.documents.length;

  return <section className="space-y-2 border-t border-slate-200 pt-4">
    <h3 className="text-sm font-medium text-slate-700">{text('model')}</h3>
    {library.canEdit ? <div className="flex flex-wrap items-center gap-2">
      <AppSelect aria-label={text('model')} placeholder={text('choose')} value={selected}
        disabled={busy || choices.isPending || choices.isError} className="w-full max-w-xs"
        options={(choices.data ?? []).map(choice => ({ value: choice.endpointId,
          label: `${choice.name}${choice.systemDefault ? ` (${text('systemDefault')})` : ''}` }))}
        onChange={id => { setSelected(id); setError(''); setNotice(''); }} />
      <Button type="button" size="sm" variant="outline" disabled={busy || !valid}
        onClick={() => { if (count > 0) setConfirming(true); else change.mutate(selected); }}>{text('save')}</Button>
    </div> : <p className="text-sm text-slate-600">{library.embeddingModelName || text('choose')}</p>}
    <p className="text-xs text-slate-500">{text('help')}</p>
    {choices.isError && <p role="alert" className="flex items-center gap-2 text-sm text-destructive">{text('loadFailed')}<Button type="button" size="sm" variant="outline" onClick={() => void choices.refetch()}>{text('reload')}</Button></p>}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    {notice && <p role="status" className="text-sm text-emerald-700">{notice}</p>}
    <ConfirmActionDialog open={confirming} title={text('title')}
      description={t('knowledge.rebind.description', { count })} confirmVariant="destructive"
      confirmText={text('confirm')} cancelText={text('cancel')} isConfirming={change.isPending}
      onConfirm={() => change.mutate(selected)} onClose={() => { if (!change.isPending) setConfirming(false); }} />
  </section>;
}
