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

import { useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { CheckCircle2, Download, ExternalLink, Eye, EyeOff, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { ConfirmActionDialog } from '@/components/ConfirmDeleteDialog';
import { createModel, discoverModels, modelDiscoveryErrorKey, modelError, searchModelOptions, testModelDraft, updateModel, type ModelEndpoint, type ModelInput, type ModelKind, type ModelProvider, type ModelProviderPreset } from '@/api/model';
import { ServerProviderSelect } from '@/components/ServerProviderSelect';
import { providerPresets } from '../modelProviders';
import { ModelSearchSelect } from './ModelSearchSelect';
import { can } from '@/api/auth';
import { useTabDraft } from '@/hooks/useTabDraft';

export function ModelEditorDrawer({ kind, model, onClose, onSaved }: {
  kind: ModelKind; model?: ModelEndpoint; onClose: () => void; onSaved: () => void;
}) {
  const { t, i18n } = useTranslation();
  const key = 'modelSettings.manage.';
  const initial = {
    provider: model?.provider ?? 'openai' as ModelProvider,
    modelName: model?.modelName ?? '',
    displayName: model?.displayName ?? '',
    baseUrl: model ? model.baseUrl || providerPresets[model.provider].baseUrl : '',
    secret: '', replaceSecret: !model, makeCurrent: false,
    knowledgePdf: model?.knowledgePdf ?? false,
  };
  const scope = `model:${kind}:${model?.endpointId ?? 'new'}`;
  const [draft, setDraft, clearDraft] = useTabDraft(scope, initial);
  const [selectedPreset, setSelectedPreset, clearPreset] = useTabDraft<ModelProviderPreset>(scope + ':preset', {
    id: model?.presetId || model?.provider || '', name: model ? model.providerName || t(`modelSettings.provider.${initial.provider}`) : '',
    provider: initial.provider, baseUrl: initial.baseUrl, keysUrl: model?.providerKeysUrl || undefined,
  });
  const drawerRef = useRef<HTMLDivElement>(null);
  const [connectionRevision, setConnectionRevision] = useState(0);
  const [returnFocus] = useState(() => typeof document !== 'undefined' ? document.activeElement as HTMLElement | null : null);
  const [showSecret, setShowSecret] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [urlTouched, setUrlTouched] = useState(false);
  const test = useMutation({ mutationFn: testModelDraft });
  const discovery = useMutation({ mutationFn: discoverModels });
  const save = useMutation({
    mutationFn: (input: ModelInput) => model ? updateModel(model.endpointId, input) : createModel(input),
    onSuccess: () => { clearDraft(); clearPreset(); onSaved(); onClose(); },
  });
  const busy = test.isPending || save.isPending || discovery.isPending;
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);
  const urlValid = (() => { try { return ['http:', 'https:'].includes(new URL(draft.baseUrl).protocol); } catch { return false; } })();
  const ready = Boolean(selectedPreset.id) && Boolean(draft.modelName.trim()) && urlValid && (!draft.replaceSecret || Boolean(draft.secret.trim()));
  const preset = selectedPreset;
  function changeBaseUrl(baseUrl: string) {
    setSelectedPreset(current => current.id === 'custom' ? current : {
      id: 'custom', name: t('modelSettings.manage.customProviderName'), provider: 'custom', baseUrl: '', keysUrl: undefined,
    });
    change({ baseUrl, provider: 'custom', ...(model ? { replaceSecret: true, secret: '' } : {}) });
  }
  function change(patch: Partial<typeof draft>, connectionChanged = true) {
    setDraft(current => ({ ...current, ...patch }));
    if (connectionChanged) test.reset();
    if (patch.provider !== undefined || patch.baseUrl !== undefined || patch.secret !== undefined || patch.replaceSecret !== undefined) {
      discovery.reset(); setConnectionRevision(value => value + 1);
    }
    save.reset();
  }
  function input(): ModelInput {
    return {
      kind, provider: draft.provider, modelName: draft.modelName.trim(),
      displayName: draft.displayName.trim() || draft.modelName.trim(),
      baseUrl: draft.baseUrl.trim(),
      ...(draft.replaceSecret ? { secret: draft.secret.trim() } : {}),
      ...(model ? { endpointId: model.endpointId } : {}), makeCurrent: draft.makeCurrent,
      ...(kind === 'chat' ? { knowledgePdf: draft.knowledgePdf ?? model?.knowledgePdf ?? false } : {}),
    };
  }
  function close() { if (!busy) { if (dirty) setDiscard(true); else onClose(); } }
  return <>
    <Dialog open onOpenChange={open => { if (!open) close(); }}>
      <DialogContent ref={drawerRef} tabIndex={-1} size="md" aria-describedby={undefined}
        onOpenAutoFocus={event => { event.preventDefault(); drawerRef.current?.focus(); }}
        onCloseAutoFocus={event => {
          event.preventDefault();
          // A newly opened drawer must keep focus when an earlier one finishes closing.
          if (!document.querySelector('[role="dialog"]')) returnFocus?.focus();
        }}
        className="inset-y-0 left-auto right-0 top-0 h-dvh max-h-none w-full max-w-2xl translate-x-0 translate-y-0 rounded-none border-y-0 border-r-0">
        <DialogHeader>
          <DialogTitle>{t(key + (model ? 'configureTitle' : kind === 'chat' ? 'addChat' : 'addEmbedding'))}</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-5">
          <fieldset disabled={busy} className="space-y-5 disabled:opacity-70">
            <section className="space-y-4">
            <h3 className="text-xs font-semibold text-slate-500">{t(key + 'modelInformation')}</h3>
            <Field label={t('modelSettings.providerLabel')} htmlFor="model-provider">
              <ServerProviderSelect id="model-provider" kind={kind} value={preset.id} displayValue={preset.name}
                placeholder={t(key + 'selectProvider')}
                disabled={Boolean(model) || busy}
                onChange={selected => { if (selected) { setSelectedPreset(selected); change({ provider: selected.provider, baseUrl: selected.baseUrl, modelName: '', displayName: '' }); setUrlTouched(false); } }} />
            </Field>
            <Field label={t(key + 'modelId')} htmlFor="model-id">
              <ModelSearchSelect id="model-id" label={t(key + 'modelId')} value={draft.modelName} freeText
                scope={`models:${kind}:${preset.id}:${connectionRevision}`} disabled={busy || !preset.id} placeholder={t(key + 'modelPlaceholder')}
                load={async (q, signal, page, snapshot) => { const data = await searchModelOptions(input(), preset.id, q, signal, page, snapshot); return { ...data, items: data.items.map(name => ({ value: name, label: name })) }; }}
                onChange={modelName => change({ modelName })} />
              <div className="mt-2 flex items-center justify-between gap-3">
                <p className="text-xs text-slate-500">{t(key + 'manualModelHint')}</p>
                {can('model.create') ? <Button size="sm" type="button" variant="outline" className="h-8 px-2.5 text-xs" disabled={busy || !urlValid || (draft.replaceSecret && !draft.secret.trim())} onClick={() => discovery.mutate(input(), { onSettled: () => setConnectionRevision(value => value + 1) })}>
                  {discovery.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}{t(key + 'fetchModels')}
                </Button> : null}
              </div>
              {discovery.isError ? <p role="status" className="mt-2 text-xs text-slate-500">{t(modelDiscoveryErrorKey(discovery.error))}</p> : null}
              {discovery.data ? <p role="status" className="mt-2 text-xs text-slate-500">{t(key + (!discovery.data.complete ? 'modelsPartial' : discovery.data.items.length ? 'modelsFetched' : 'modelsEmpty'), { count: discovery.data.total })}</p> : null}
            </Field>
            <Field label={t(key + 'displayName')} htmlFor="model-display-name">
              <Input id="model-display-name" value={draft.displayName} onChange={e => change({ displayName: e.target.value }, false)} placeholder={draft.modelName || t(key + 'displayNamePlaceholder')} />
            </Field>
            </section>
            <section className="space-y-4 border-t border-slate-100 pt-5">
              <h3 className="text-xs font-semibold text-slate-500">{t(key + 'connectionSettings')}</h3>
              <Field label={t('modelSettings.baseUrl')} htmlFor="model-url">
                <Input id="model-url" type="url" value={draft.baseUrl} onBlur={() => setUrlTouched(true)} onChange={e => changeBaseUrl(e.target.value)} aria-invalid={urlTouched && !urlValid} placeholder="https://api.example.com/v1" className="font-mono text-xs" />
                {urlTouched && !urlValid ? <p role="alert" className="mt-1 text-xs text-red-600">{t(key + 'invalidUrl')}</p> : null}
              </Field>
            {draft.replaceSecret ? <Field label={t(key + 'apiKey')} htmlFor="model-secret">
              <div className="relative">
                <Input id="model-secret" type={showSecret ? 'text' : 'password'} autoComplete="new-password" value={draft.secret} onChange={e => change({ secret: e.target.value })} className="pr-12 font-mono" />
                <button type="button" onClick={() => setShowSecret(value => !value)} aria-label={t(key + (showSecret ? 'hideKey' : 'showKey'))} className="absolute right-0 top-0 flex h-10 w-11 items-center justify-center rounded text-slate-500 focus-visible:ring-2 focus-visible:ring-ring">
                  {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <div className="mt-2 flex justify-end gap-3 text-xs">
                {model ? <button type="button" onClick={() => change({ replaceSecret: false, secret: '' })} className="text-slate-500 hover:underline">{t(key + 'keepKey')}</button> : null}
                {preset.keysUrl ? <a href={preset.keysUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">{t(key + 'getKey')}<ExternalLink className="h-3 w-3" /></a> : null}
              </div>
            </Field> : <div className="flex items-center justify-between rounded-lg border border-border bg-slate-50 px-3 py-3 text-sm">
              <span className="text-slate-600">{t(key + 'keyConfigured')}</span>
              <Button size="sm" variant="ghost" onClick={() => change({ replaceSecret: true })}>{t(key + 'replaceKey')}</Button>
            </div>}
            </section>
          </fieldset>
          <div className="space-y-3 border-t border-border pt-5">
            {test.data ? <p role="status" className={test.data.ok ? 'flex items-center gap-2 text-sm text-emerald-700' : 'text-sm text-red-600'}>
              {test.data.ok ? <CheckCircle2 className="h-4 w-4" /> : null}
              {test.data.ok ? t(key + 'testPassed', { ms: test.data.elapsedMs }) : test.data.message || t(key + 'operationError')}
            </p> : null}
            {test.isError ? <p role="alert" className="text-sm text-red-600">{modelError(test.error, t(key + 'operationError'))}</p> : null}
            {model?.lastTestAt && !test.data ? <p className="text-xs text-slate-500">{t(key + 'lastTest')}: {new Date(model.lastTestAt).toLocaleString(i18n.language)} · {t(key + (model.lastTestStatus === 'passed' ? 'passed' : 'failed'))}</p> : null}
          </div>
          {!model?.current ? <label className="flex items-center gap-2 text-sm text-slate-700"><input type="checkbox" checked={draft.makeCurrent} disabled={busy} onChange={e => change({ makeCurrent: e.target.checked }, false)} />{t(key + (kind === 'chat' ? 'setDefaultChat' : 'setDefaultEmbedding'))}</label> : null}
          {draft.makeCurrent ? <p className="text-xs leading-5 text-slate-500">{t(key + (kind === 'chat' ? 'defaultImpact' : 'embeddingImpact'))}</p> : null}
          {kind === 'chat' ? <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm text-slate-700"><input type="checkbox" checked={draft.knowledgePdf ?? model?.knowledgePdf ?? false} disabled={busy} onChange={e => change({ knowledgePdf: e.target.checked })} />{t(key + 'knowledgePdf')}</label>
            <p className="text-xs leading-5 text-slate-500">{t(key + 'knowledgePdfHelp')}</p>
          </div> : null}
          {save.isError ? <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{modelError(save.error, t(key + 'operationError'))}</p> : null}
        </DialogBody>
        <DialogFooter>
          {can('model.test') ? <Button className="mr-auto" variant="outline" disabled={!ready || busy} onClick={() => test.mutate(input())}>
            {test.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : null}{t(key + (test.isPending ? 'testing' : 'test'))}
          </Button> : null}
          <Button variant="outline" disabled={busy} onClick={close}>{t('chat.cancel')}</Button>
          <Button disabled={!ready || busy || (Boolean(model) && !dirty)} onClick={() => save.mutate(input())}>
            {save.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : null}{t(key + (save.isPending ? 'saving' : model ? 'saveChanges' : 'save'))}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
    <ConfirmActionDialog open={discard} title={t(key + 'discardTitle')} description={t(key + 'discardHint')} confirmVariant="destructive" confirmText={t(key + 'discard')} cancelText={t(key + 'keepEditing')} onConfirm={() => { clearDraft(); clearPreset(); onClose(); }} onClose={() => setDiscard(false)} />
  </>;
}
function Field({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return <div><label htmlFor={htmlFor} className="mb-2 block text-sm font-medium text-slate-700">{label}</label>{children}</div>;
}
