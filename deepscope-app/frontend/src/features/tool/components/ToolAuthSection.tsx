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

import React from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import { AppSelect } from '@/components/ui/select';
import type { ToolAuthConfig, ToolAuthAction, ToolAuthType } from '@/api/tool';

export interface ToolAuthSectionProps {
  value?: ToolAuthConfig | null;
  onChange: (value: ToolAuthConfig) => void;
  mode?: 'create' | 'edit';
  hasExistingCredential?: boolean;
  className?: string;
}

export function ToolAuthSection({
  value,
  onChange,
  mode = 'create',
  hasExistingCredential = false,
  className = '',
}: ToolAuthSectionProps) {
  const { t } = useTranslation();

  const isEdit = mode === 'edit';
  const action: ToolAuthAction = value?.action ?? (isEdit ? 'keep' : 'replace');
  const authType: ToolAuthType = value?.type ?? (isEdit ? 'apiKey' : 'none');

  const handleActionChange = (newAction: ToolAuthAction) => {
    if (newAction === 'keep') {
      onChange({ action: 'keep' });
    } else if (newAction === 'clear') {
      onChange({ action: 'clear' });
    } else {
      onChange({
        action: 'replace',
        type: value?.type && value.type !== 'none' ? value.type : 'apiKey',
        name: value?.name ?? (value?.type === 'apiKey' ? 'X-API-Key' : undefined),
        in: value?.in ?? 'header',
        value: value?.value ?? '',
        username: value?.username ?? '',
        password: value?.password ?? '',
      });
    }
  };

  const handleTypeChange = (newType: ToolAuthType) => {
    if (isEdit) {
      onChange({
        action: 'replace',
        type: newType,
        name: newType === 'apiKey' ? (value?.name || 'X-API-Key') : undefined,
        in: newType === 'apiKey' ? (value?.in || 'header') : undefined,
        value: value?.value ?? '',
        username: value?.username ?? '',
        password: value?.password ?? '',
      });
    } else {
      if (newType === 'none') {
        onChange({ type: 'none' });
      } else {
        onChange({
          type: newType,
          name: newType === 'apiKey' ? (value?.name || 'X-API-Key') : undefined,
          in: newType === 'apiKey' ? (value?.in || 'header') : undefined,
          value: value?.value ?? '',
          username: value?.username ?? '',
          password: value?.password ?? '',
        });
      }
    }
  };

  const updateField = (field: keyof ToolAuthConfig, val: string) => {
    onChange({
      ...(value ?? {}),
      ...(isEdit ? { action: 'replace' } : {}),
      type: authType,
      [field]: val,
    });
  };

  const showInputs = isEdit ? action === 'replace' : authType !== 'none';

  return (
    <div className={`space-y-3 rounded-xl border border-border/70 bg-slate-50/50 p-3.5 ${className}`}>
      <div className="flex items-center justify-between">
        <span className="text-[13px] font-medium text-slate-700">{t('tool.auth.title')}</span>
        {isEdit ? (
          <span className="text-[12px] text-slate-500">
            {hasExistingCredential ? t('tool.auth.hasExisting') : t('tool.auth.noExisting')}
          </span>
        ) : null}
      </div>

      {isEdit ? (
        <div>
          <label className="mb-1 block text-[12.5px] font-medium text-slate-600">
            {t('tool.auth.action')}
          </label>
          <AppSelect
            className="w-full"
            aria-label={t('tool.auth.action')}
            value={action}
            onChange={(value) => handleActionChange(value as ToolAuthAction)}
            options={[
              { value: 'keep', label: t('tool.auth.actionKeep') },
              { value: 'replace', label: t('tool.auth.actionReplace') },
              { value: 'clear', label: t('tool.auth.actionClear') },
            ]}
          />
        </div>
      ) : null}

      {(!isEdit || action === 'replace') ? (
        <div>
          <label className="mb-1 block text-[12.5px] font-medium text-slate-600">
            {t('tool.auth.mode')}
          </label>
          <AppSelect
            className="w-full"
            aria-label={t('tool.auth.mode')}
            value={authType}
            onChange={(value) => handleTypeChange(value as ToolAuthType)}
            options={[
              ...(isEdit ? [] : [{ value: 'none', label: t('tool.auth.typeNone') }]),
              { value: 'apiKey', label: t('tool.auth.typeApiKey') },
              { value: 'bearer', label: t('tool.auth.typeBearer') },
              { value: 'basic', label: t('tool.auth.typeBasic') },
            ]}
          />
        </div>
      ) : null}

      {showInputs && authType === 'apiKey' ? (
        <div className="space-y-3 pt-1">
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600">
              {t('tool.auth.placement')}
            </label>
            <AppSelect
              className="w-full"
              aria-label={t('tool.auth.placement')}
              value={value?.in ?? 'header'}
              onChange={(next) => updateField('in', next)}
              options={[
                { value: 'header', label: t('tool.auth.placementHeader') },
                { value: 'query', label: t('tool.auth.placementQuery') },
              ]}
            />
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600" htmlFor="tool-auth-apikey-name">
              {t('tool.auth.keyName')}
            </label>
            <Input
              id="tool-auth-apikey-name"
              placeholder={t('tool.auth.keyNamePlaceholder')}
              value={value?.name ?? 'X-API-Key'}
              onChange={(e) => updateField('name', e.target.value)}
            />
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600" htmlFor="tool-auth-apikey-value">
              {t('tool.auth.keyValue')}
            </label>
            <Input
              id="tool-auth-apikey-value"
              type="password"
              placeholder={t('tool.auth.keyValuePlaceholder')}
              value={value?.value ?? ''}
              onChange={(e) => updateField('value', e.target.value)}
            />
          </div>
        </div>
      ) : null}

      {showInputs && authType === 'bearer' ? (
        <div className="space-y-3 pt-1">
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600" htmlFor="tool-auth-bearer-token">
              {t('tool.auth.token')}
            </label>
            <Input
              id="tool-auth-bearer-token"
              type="password"
              placeholder={t('tool.auth.tokenPlaceholder')}
              value={value?.value ?? ''}
              onChange={(e) => updateField('value', e.target.value)}
            />
          </div>
        </div>
      ) : null}

      {showInputs && authType === 'basic' ? (
        <div className="space-y-3 pt-1">
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600" htmlFor="tool-auth-basic-username">
              {t('tool.auth.username')}
            </label>
            <Input
              id="tool-auth-basic-username"
              placeholder={t('tool.auth.usernamePlaceholder')}
              value={value?.username ?? ''}
              onChange={(e) => updateField('username', e.target.value)}
            />
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-medium text-slate-600" htmlFor="tool-auth-basic-password">
              {t('tool.auth.password')}
            </label>
            <Input
              id="tool-auth-basic-password"
              type="password"
              placeholder={t('tool.auth.passwordPlaceholder')}
              value={value?.password ?? ''}
              onChange={(e) => updateField('password', e.target.value)}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
