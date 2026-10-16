/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

import React, { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { AppSelect } from '@/components/ui/select';
import { apiFetch } from '@/lib/apiClient';
import type { LibraryDetail } from '../types';
import { KnowledgeHelp as HelpTooltip } from './KnowledgeHelp';

type Settings = Pick<LibraryDetail, 'chunkSize' | 'overlap' | 'recallLimit' | 'scoreThreshold'>;
type Draft = Record<keyof Settings, string>;
type Props = { library: LibraryDetail; onChanged: () => void };

const presets: Record<string, Settings> = {
  general: { chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2 },
  qa: { chunkSize: 256, overlap: 30, recallLimit: 3, scoreThreshold: 0.4 },
  long: { chunkSize: 1024, overlap: 100, recallLimit: 8, scoreThreshold: 0.15 },
};

function toDraft(settings: Settings): Draft {
  return {
    chunkSize: String(settings.chunkSize), overlap: String(settings.overlap),
    recallLimit: String(settings.recallLimit), scoreThreshold: String(settings.scoreThreshold),
  };
}

function parseSettings(draft: Draft): Settings | null {
  if (Object.values(draft).some((value) => !value.trim())) return null;
  const values = {
    chunkSize: Number(draft.chunkSize), overlap: Number(draft.overlap),
    recallLimit: Number(draft.recallLimit), scoreThreshold: Number(draft.scoreThreshold),
  };
  if (!Number.isInteger(values.chunkSize) || values.chunkSize < 1 ||
      !Number.isInteger(values.overlap) || values.overlap < 0 || values.overlap >= values.chunkSize ||
      !Number.isInteger(values.recallLimit) || values.recallLimit < 1 || values.recallLimit > 20 ||
      !Number.isFinite(values.scoreThreshold) || values.scoreThreshold < 0 || values.scoreThreshold > 1) return null;
  return values;
}

export function RetrievalSettingsTab(props: Props) {
  return <SettingsForm key={props.library.libraryId} {...props} />;
}

function SettingsForm({ library, onChanged }: Props) {
  const { t } = useTranslation();
  const id = useId();
  const [saved, setSaved] = useState<Settings>({
    chunkSize: library.chunkSize, overlap: library.overlap,
    recallLimit: library.recallLimit, scoreThreshold: library.scoreThreshold,
  });
  const [draft, setDraft] = useState(() => toDraft(library));
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const text = (key: string) => t(`knowledge.editor.settings.${key}`);
  const numeric = parseSettings(draft);
  const savedDraft = toDraft(saved);
  const dirty = (Object.keys(draft) as (keyof Settings)[]).some((key) => draft[key] !== savedDraft[key]);
  const chunkingChanged = draft.chunkSize !== savedDraft.chunkSize || draft.overlap !== savedDraft.overlap;
  const activePreset = Object.entries(presets).find(([, values]) => numeric &&
    (Object.keys(values) as (keyof Settings)[]).every((key) => numeric[key] === values[key]))?.[0] ?? 'custom';

  const saveMutation = useMutation({
    mutationFn: (values: Settings) => apiFetch<void>(
      `/api/advisor/knowledge/${encodeURIComponent(library.libraryId)}`,
      { method: 'PATCH', body: JSON.stringify(values) },
    ),
    onSuccess: (_, values) => {
      const changedChunks = values.chunkSize !== saved.chunkSize || values.overlap !== saved.overlap;
      setSaved(values);
      setDraft(toDraft(values));
      setNotice(changedChunks ? 'savedReprocess' : 'saved');
      setError('');
      onChanged();
    },
    onError: () => setError(text('saveFailed')),
  });

  function updateDraft(next: Draft) {
    setDraft(next);
    setNotice('');
    setError('');
  }

  function renderField(key: keyof Settings, label: string, help: string, min: number, max?: number) {
    return <div className="space-y-2">
      <div className="flex items-center gap-1.5 text-sm text-slate-700">
        <label htmlFor={`${id}-${key}`}>{text(label)}</label>
        <HelpTooltip label={`${text(label)}?`}>{text(help)}</HelpTooltip>
      </div>
      <div className="flex items-center gap-2">
        <Input id={`${id}-${key}`} type="number" min={min} max={max}
          step={key === 'scoreThreshold' ? '0.01' : '1'} value={draft[key]}
          disabled={!library.canEdit || saveMutation.isPending}
          onChange={(event) => updateDraft({ ...draft, [key]: event.target.value })}
          className="max-w-52" />
        {(key === 'chunkSize' || key === 'overlap') && <span className="text-xs text-slate-500">{text('characters')}</span>}
      </div>
    </div>;
  }

  return <form noValidate className="max-w-3xl space-y-6" onSubmit={(event) => {
    event.preventDefault();
    if (!library.canEdit || saveMutation.isPending || !dirty) return;
    if (!numeric) { setError(text('invalid')); return; }
    setError('');
    saveMutation.mutate(numeric);
  }}>
    {!library.canEdit && <p className="text-sm text-slate-500">{text('readOnly')}</p>}
    <div className="space-y-2">
      <div className="flex items-center gap-1.5 text-sm font-medium text-slate-700">
        <span>{text('preset')}</span><HelpTooltip label={`${text('preset')}?`}>{text('presetHelp')}</HelpTooltip>
      </div>
      <AppSelect aria-label={text('preset')} className="w-full max-w-xs" value={activePreset}
        disabled={!library.canEdit || saveMutation.isPending}
        options={['general', 'qa', 'long', 'custom'].map((value) => ({ value, label: text(value) }))}
        onChange={(value) => {
          if (value === 'custom') setExpanded(true);
          else updateDraft(toDraft(presets[value]));
        }} />
      <p className="text-xs text-slate-500">{text(`${activePreset}Desc`)}</p>
    </div>

    <section className="space-y-5 border-t border-slate-200 pt-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-slate-800">{text('advanced')}</h3>
        <Button type="button" size="sm" variant="outline" aria-expanded={expanded}
          aria-controls={`${id}-advanced`} onClick={() => setExpanded(!expanded)}>
          {text(expanded ? 'collapse' : 'expand')}
        </Button>
      </div>
      {expanded && <div id={`${id}-advanced`} className="space-y-6">
        <fieldset className="space-y-3">
          <legend className="text-xs font-medium text-slate-500">{text('chunking')}</legend>
          <div className="grid gap-4 sm:grid-cols-2">
            {renderField('chunkSize', 'chunkSize', 'chunkSizeHelp', 1)}
            {renderField('overlap', 'overlap', 'overlapHelp', 0)}
          </div>
        </fieldset>
        <fieldset className="space-y-3">
          <legend className="text-xs font-medium text-slate-500">{text('retrieval')}</legend>
          <div className="grid gap-4 sm:grid-cols-2">
            {renderField('recallLimit', 'recallLimit', 'recallLimitHelp', 1, 20)}
            {renderField('scoreThreshold', 'threshold', 'thresholdHelp', 0, 1)}
          </div>
        </fieldset>
      </div>}
    </section>
    {chunkingChanged && <p className="text-xs text-slate-500">{text('chunkingNotice')}</p>}
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
    {notice && <p role="status" className="text-sm text-emerald-600">{text(notice)}</p>}
    {library.canEdit && <div className="flex justify-end gap-2 border-t border-slate-200 pt-4">
      {dirty && <Button type="button" size="sm" variant="outline" disabled={saveMutation.isPending}
        onClick={() => updateDraft(toDraft(saved))}>{text('reset')}</Button>}
      <Button type="submit" size="sm" disabled={!dirty || saveMutation.isPending}>
        {text(saveMutation.isPending ? 'saving' : 'save')}
      </Button>
    </div>}
  </form>;
}
