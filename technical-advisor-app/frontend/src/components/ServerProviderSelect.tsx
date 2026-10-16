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
import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { searchProviders, type ModelKind, type ModelProviderPreset } from '@/api/model';
import { RemoteSearchSelect } from './RemoteSearchSelect';

/** Only fetched choices are retained; matching is performed by the provider endpoint. */
export function ServerProviderSelect({ id, kind, value, displayValue, onChange, disabled, allowClear = false, placeholder, label }: {
  id: string; kind: ModelKind; value: string; displayValue?: string; label?: string; placeholder?: string;
  onChange: (preset: ModelProviderPreset | null) => void; disabled?: boolean; allowClear?: boolean;
}) {
  const { t } = useTranslation();
  const choices = useRef(new Map<string, ModelProviderPreset>());
  return <RemoteSearchSelect id={id} label={label || t('modelSettings.providerLabel')} value={value}
    displayValue={displayValue} scope={`providers:${kind}`} disabled={disabled} allowClear={allowClear}
    placeholder={placeholder || t('modelSettings.manage.selectProvider')}
    load={async (q, signal) => {
      const result = await searchProviders(kind, q, signal);
      choices.current.clear();
      result.items.forEach(item => choices.current.set(item.id, item));
      return { ...result, items: result.items.map(item => ({ value: item.id, label: item.name })) };
    }}
    onChange={id => { if (!id) onChange(null); else { const choice = choices.current.get(id); if (choice) onChange(choice); } }} />;
}
