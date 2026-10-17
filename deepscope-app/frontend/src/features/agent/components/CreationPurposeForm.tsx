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
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { AlertCircle, Check, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { apiFetch } from '@/lib/apiClient';
import {
  getGeneration,
  submitGeneration,
} from '@/api/agentCreation';

interface CreationPurposeFormProps {
  draftId: string | null;
  revision: number;
  purpose: string;
  onChangePurpose: (val: string) => void;
  name: string;
  onChangeName: (val: string) => void;
  description: string;
  onChangeDescription: (val: string) => void;
  behavior: string;
  onChangeBehavior: (val: string) => void;
  onApplyCandidate?: (candidate: {
    name?: string;
    description?: string;
    behavior?: string;
  }) => void;
  onFlush?: () => Promise<any>;
}

export function CreationPurposeForm({
  draftId,
  revision,
  purpose,
  onChangePurpose,
  name,
  onChangeName,
  description,
  onChangeDescription,
  behavior,
  onChangeBehavior,
  onFlush,
}: CreationPurposeFormProps) {
  const { t } = useTranslation();
  const [isGenerating, setIsGenerating] = useState(false);
  const [generationError, setGenerationError] = useState<string | null>(null);
  const [generateSuccess, setGenerateSuccess] = useState(false);

  // Query active model capability to verify model configuration prerequisite
  const activeModelQuery = useQuery({
    queryKey: ['advisor', 'models', 'active'],
    queryFn: () =>
      apiFetch<{ modelName: string; contextWindow?: number }>(
        '/api/advisor/models/active',
      ),
    staleTime: 60_000,
  });

  const isModelConfigured = Boolean(
    activeModelQuery.data?.modelName && activeModelQuery.data.modelName !== 'none',
  );

  const handleGenerateBehavior = async () => {
    if (isGenerating || !purpose.trim()) return;

    if (!isModelConfigured) {
      setGenerationError(
        t('agent.creation.noModelConfigured', '未检测到可用模型，请先前往模型设置配置默认模型。'),
      );
      return;
    }

    let activeDraftId = draftId;
    let activeRevision = revision;
    if (!activeDraftId && onFlush) {
      const flushed = await onFlush();
      if (!flushed) {
        setGenerationError(
          t('agent.creation.saveFailed', '草稿保存失败，请检查服务连接后重试'),
        );
        return;
      }
      activeDraftId = flushed.draftId;
      activeRevision = flushed.revision;
    }

    if (!activeDraftId) return;

    setIsGenerating(true);
    setGenerationError(null);
    setGenerateSuccess(false);

    try {
      const initial = await submitGeneration(activeDraftId, activeRevision);
      const opId = initial.operationId;

      const startTime = Date.now();
      const poll = async () => {
        if (Date.now() - startTime > 35000) {
          throw new Error(t('agent.creation.generationTimeout', '生成超时，请稍后重试。'));
        }
        const op = await getGeneration(activeDraftId!, opId);
        if (op.state === 'succeeded') {
          const candidate = op.candidate;
          if (candidate?.behavior) {
            onChangeBehavior(candidate.behavior);
          }
          if (!name.trim() && candidate?.name) {
            onChangeName(candidate.name);
          }
          if (!description.trim() && candidate?.description) {
            onChangeDescription(candidate.description);
          }
          setGenerateSuccess(true);
          setIsGenerating(false);
          setTimeout(() => setGenerateSuccess(false), 3000);
        } else if (
          op.state === 'failed' ||
          op.state === 'canceled' ||
          op.state === 'interrupted'
        ) {
          throw new Error(
            op.errorMessage || t('agent.creation.generateFailed', '模型生成失败，请稍后重试或手动输入。'),
          );
        } else {
          setTimeout(poll, 1500);
        }
      };
      await poll();
    } catch (err: any) {
      setIsGenerating(false);
      setGenerationError(
        err?.message || t('agent.creation.generateFailed', '生成失败，请稍后重试。'),
      );
    }
  };

  const hasPurpose = purpose.trim().length > 0;

  return (
    <div className="space-y-6">
      {/* 1. Purpose Section */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <label className="text-sm font-semibold text-slate-800">
            {t('agent.creation.purposeLabel')}
          </label>
          <span className="text-xs text-slate-400">
            {t('agent.creation.purposeHint')}
          </span>
        </div>
        <Textarea
          rows={3}
          value={purpose}
          onChange={(e) => onChangePurpose(e.target.value)}
          placeholder={t(
            'agent.creation.purposePlaceholder',
            '描述智能体的应用场景与核心任务，例如：代码审查、架构设计分析或文档提炼',
          )}
          className="resize-none text-xs leading-relaxed"
        />
      </div>

      {/* 2. Basic Information (Name & Description) */}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <label className="text-sm font-semibold text-slate-800">
            {t('agent.creation.nameLabel', '智能体名称')}
          </label>
          <Input
            value={name}
            onChange={(e) => onChangeName(e.target.value)}
            placeholder={t('agent.creation.namePlaceholder', '例如：架构评审助手')}
            maxLength={64}
          />
        </div>
        <div className="space-y-1.5">
          <label className="text-sm font-semibold text-slate-800">
            {t('agent.creation.descriptionLabel', '简介')}
          </label>
          <Input
            value={description}
            onChange={(e) => onChangeDescription(e.target.value)}
            placeholder={t('agent.creation.descriptionPlaceholder', '一句话简要介绍智能体职责')}
            maxLength={200}
          />
        </div>
      </div>

      {/* 3. Work Requirements & System Prompt */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <label className="text-sm font-semibold text-slate-800">
              {t('agent.creation.behaviorLabel', '工作要求与系统提示词')}
            </label>
            {/* AI Generate Button: rendered only when purpose has a value */}
            {hasPurpose && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={isGenerating}
                onClick={handleGenerateBehavior}
                className="h-6 px-2 text-xs font-medium text-violet-700 bg-violet-50 hover:bg-violet-100 hover:text-violet-800 border-violet-200"
              >
                {isGenerating ? (
                  <Loader2 className="mr-1 h-3 w-3 animate-spin text-violet-600" />
                ) : null}
                {isGenerating
                  ? t('agent.creation.generating', '生成中...')
                  : t('agent.creation.generateAi', 'AI 生成')}
              </Button>
            )}
            {generateSuccess && (
              <span className="flex items-center gap-1 text-[11px] text-emerald-600 font-medium">
                <Check className="h-3 w-3" />
                {t('agent.creation.generateSuccess', '已自动填充')}
              </span>
            )}
          </div>
          <span className="text-xs text-slate-400">
            {t('agent.creation.behaviorHint', '设定行为规范与输出约束')}
          </span>
        </div>

        {generationError && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-2.5 text-xs text-red-700 flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <AlertCircle className="h-3.5 w-3.5 shrink-0 text-red-600" />
              <span>{generationError}</span>
            </div>
            <button
              type="button"
              onClick={() => setGenerationError(null)}
              className="text-xs text-red-500 hover:text-red-800 underline ml-2"
            >
              {t('agent.creation.dismiss', '关闭')}
            </button>
          </div>
        )}

        <Textarea
          rows={7}
          value={behavior}
          onChange={(e) => onChangeBehavior(e.target.value)}
          placeholder={t(
            'agent.creation.behaviorPlaceholder',
            '指定角色定位、工作流程及输出规范...',
          )}
          className="font-mono text-xs leading-relaxed"
        />
      </div>
    </div>
  );
}
