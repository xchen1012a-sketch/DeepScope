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
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useIsMutating } from '@tanstack/react-query';
import { ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { advisorKeys } from '@/api/advisor';
import { ModelPicker } from '@/features/chat/components/ModelPicker';
import type { ReasoningEffort } from '@/features/chat/aguiClient';
import {
  AttachmentStrip,
  ComposerToolbar,
  useComposerAttachments,
} from '@/features/chat/components/ComposerToolbar';

export default function HomePage() {
  const { t } = useTranslation();
  const changingModel = useIsMutating({ mutationKey: advisorKeys.modelActivation }) > 0;
  const navigate = useNavigate();
  const [value, setValue] = useState('');
  const [modelEndpointId, setModelEndpointId] = useState<string | null>(null);
  const [thinking, setThinking] = useState(false);
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const {
    attachments,
    uploading,
    uploadError,
    fileInputRef,
    openFilePicker,
    addFiles,
    removeAttachment,
    handlePaste,
    handleDrop,
  } = useComposerAttachments();

  const canStart = (value.trim().length > 0 || attachments.length > 0) && !uploading && !changingModel;

  /** 开始一次任务：跳到会话页并把文本、深度思考开关与已解析附件一并传入。 */
  const start = (text: string) => {
    const trimmed = text.trim();
    if ((!trimmed && attachments.length === 0) || uploading || changingModel) return;
    const effectiveQuestion = trimmed || attachments.map((item) => item.name).join('、');
    navigate(
      { pathname: '/chat', search: `?q=${encodeURIComponent(effectiveQuestion)}` },
      {
        state: {
          autoStart: true,
          q: trimmed,
          thinking,
          reasoningEffort,
          modelEndpointId,
          attachments,
        },
      },
    );
  };

  const applyStarter = (prompt: string) => {
    setValue(prompt);
    textareaRef.current?.focus();
  };

  const starters = [
    {
      key: 'repo',
      title: t('home.starter.repo.title'),
      desc: t('home.starter.repo.desc'),
      prompt: t('home.starter.repo.prompt'),
    },
    {
      key: 'compare',
      title: t('home.starter.compare.title'),
      desc: t('home.starter.compare.desc'),
      prompt: t('home.starter.compare.prompt'),
    },
    {
      key: 'troubleshoot',
      title: t('home.starter.troubleshoot.title'),
      desc: t('home.starter.troubleshoot.desc'),
      prompt: t('home.starter.troubleshoot.prompt'),
    },
  ];

  return (
    <div className="mx-auto flex min-h-full max-w-[700px] flex-col justify-center px-5 py-16 sm:px-6">
      <div className="text-center">
        <h1 className="text-[26px] font-semibold leading-tight tracking-tight text-slate-900 sm:text-[30px]">
          {t('home.title')}
        </h1>
      </div>

      <div
        onDragOver={(event) => event.preventDefault()}
        onDrop={handleDrop}
        className="mt-7 rounded-2xl border border-border bg-white p-3.5 shadow-sm"
      >
        <AttachmentStrip
          attachments={attachments}
          uploading={uploading}
          uploadError={uploadError}
          onRemove={removeAttachment}
        />
        <Textarea
          rows={1}
          ref={textareaRef}
          value={value}
          onPaste={handlePaste}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              start(value);
            }
          }}
          placeholder={t('home.placeholder')}
          className="min-h-[36px] resize-none border-0 p-1 text-[14.5px] leading-relaxed shadow-none focus:shadow-none focus:ring-0 focus-visible:ring-0"
        />
        <div className="mt-2 flex flex-wrap items-center justify-between gap-2 px-1 pt-1">
          <ComposerToolbar
            fileInputRef={fileInputRef}
            onPickFiles={openFilePicker}
            onFilesSelected={(files) => void addFiles(files)}
          />
          <div className="flex min-w-0 flex-wrap items-center gap-2.5">
            <ModelPicker selectedEndpointId={modelEndpointId} onSelectModel={setModelEndpointId} thinking={thinking} onToggleThinking={() => setThinking(prev => !prev)} reasoningEffort={reasoningEffort} onSelectReasoningEffort={setReasoningEffort} />
            <span className="hidden text-[12px] text-slate-400 sm:inline">{t('home.sendHint')}</span>
            <Button size="sm" onClick={() => start(value)} disabled={!canStart}>
              {t('home.send')}
              <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-2.5 sm:grid-cols-3">
        {starters.map((item) => (
          <button
            key={item.key}
            type="button"
            onClick={() => applyStarter(item.prompt)}
            className="rounded-xl border border-border bg-white p-3.5 text-left transition-colors hover:border-slate-300 hover:bg-muted/40"
          >
            <div className="text-[13px] font-semibold text-slate-900">{item.title}</div>
            <div className="mt-1 text-[12px] leading-relaxed text-slate-500">{item.desc}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
