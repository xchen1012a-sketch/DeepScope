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
import { AlertCircle, Bot, Loader2, RotateCcw, Send, User, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  createPreview,
  runPreviewTurn,
  type PreviewDto,
} from '@/api/agentCreation';

interface AgentPreviewPanelProps {
  draftId: string | null;
  revision: number;
  onClose?: () => void;
  permanent?: boolean;
}

interface Message {
  role: 'user' | 'assistant';
  content: string;
}

export function AgentPreviewPanel({
  draftId,
  revision,
  onClose,
  permanent = false,
}: AgentPreviewPanelProps) {
  const { t } = useTranslation();
  const [preview, setPreview] = useState<PreviewDto | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [isRunning, setIsRunning] = useState(false);
  const [initError, setInitError] = useState<string | null>(null);

  const initPreview = async () => {
    if (!draftId) return;
    setInitError(null);
    try {
      const p = await createPreview(draftId, revision);
      setPreview(p);
      setMessages((p.history as Message[]) || []);
    } catch (err: any) {
      setInitError(err?.message || t('agent.creation.previewInitError', '初始化沙箱试运行环境失败'));
    }
  };

  const handleSend = async () => {
    const text = input.trim();
    if (!text || !draftId || isRunning) return;

    let currentPreview = preview;
    if (!currentPreview || currentPreview.draftRevision !== revision) {
      try {
        currentPreview = await createPreview(draftId, revision);
        setPreview(currentPreview);
      } catch (err: any) {
        setInitError(err?.message || t('agent.creation.previewCreateError', '创建测试会话失败'));
        return;
      }
    }

    const newMessages: Message[] = [...messages, { role: 'user', content: text }];
    setMessages(newMessages);
    setInput('');
    setIsRunning(true);

    try {
      const res = await runPreviewTurn(draftId, currentPreview.previewId, text);
      setMessages([...newMessages, { role: 'assistant', content: res.reply }]);
    } catch (err: any) {
      setMessages([
        ...newMessages,
        {
          role: 'assistant',
          content: `[${t('agent.creation.previewFailed', '试运行失败')}: ${err?.message || '服务异常'}]`,
        },
      ]);
    } finally {
      setIsRunning(false);
    }
  };

  const isStale = preview && preview.draftRevision !== revision;

  return (
    <div className="flex h-full flex-col rounded-xl border border-slate-200 bg-white shadow-sm overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-4 py-3">
        <div className="flex items-center gap-2">
          <Bot className="h-4 w-4 text-violet-600" />
          <span className="text-sm font-semibold text-slate-800">
            {t('agent.creation.previewTitle', '沙箱试运行')}
          </span>
          <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-medium text-violet-700">
            {t('agent.creation.previewEnvTag', '隔离环境')}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setMessages([]);
              initPreview();
            }}
            title={t('agent.creation.resetPreview', '重置试运行')}
            aria-label="清空试聊"
            className="h-7 w-7 p-0 text-slate-400 hover:text-slate-600"
          >
            <RotateCcw className="h-3.5 w-3.5" />
          </Button>
          {!permanent && onClose && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={onClose}
              title={t('agent.creation.closePreview', '关闭')}
              aria-label="关闭试聊"
              className="h-7 w-7 p-0 text-slate-400 hover:text-slate-600"
            >
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>

      {/* Stale Warning */}
      {isStale && (
        <div className="flex items-center justify-between bg-amber-50 px-3 py-1.5 text-xs text-amber-800 border-b border-amber-200">
          <div className="flex items-center gap-1.5">
            <AlertCircle className="h-3.5 w-3.5 text-amber-600" />
            <span>{t('agent.creation.previewStale', '配置已更新，当前试运行基于上一版本')}</span>
          </div>
          <button
            type="button"
            onClick={() => {
              setMessages([]);
              initPreview();
            }}
            className="font-medium underline hover:text-amber-900"
          >
            {t('agent.creation.previewRestart', '重新运行')}
          </button>
        </div>
      )}

      {/* Messages Scroll Area */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3 min-h-[260px]">
        {initError && (
          <div className="rounded-lg bg-red-50 p-2.5 text-xs text-red-600">{initError}</div>
        )}

        {messages.length === 0 && !initError ? (
          <div className="flex h-full flex-col items-center justify-center text-center text-xs text-slate-400 py-10 px-4">
            <Bot className="mb-2.5 h-8 w-8 text-slate-300" />
            <p className="leading-relaxed">
              {t(
                'agent.creation.previewEmptyHint',
                '输入测试消息以验证智能体的实际行为与输出规范。试运行完全隔离，不产生正式会话记录。',
              )}
            </p>
          </div>
        ) : null}

        {messages.map((msg, index) => (
          <div
            key={index}
            className={`flex items-start gap-2.5 ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            {msg.role === 'assistant' && (
              <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 text-xs">
                <Bot className="h-3.5 w-3.5" />
              </div>
            )}
            <div
              className={`max-w-[80%] rounded-xl px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap ${
                msg.role === 'user'
                  ? 'bg-violet-600 text-white'
                  : 'bg-slate-100 text-slate-800'
              }`}
            >
              {msg.content}
            </div>
            {msg.role === 'user' && (
              <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-600 text-xs">
                <User className="h-3.5 w-3.5" />
              </div>
            )}
          </div>
        ))}

        {isRunning && (
          <div className="flex items-center gap-2 text-xs text-slate-400 pl-2">
            <Loader2 className="h-3.5 w-3.5 animate-spin text-violet-500" />
            <span>{t('agent.creation.previewReplying', '正在回复...')}</span>
          </div>
        )}
      </div>

      {/* Input Form */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleSend();
        }}
        className="flex items-center gap-2 border-t border-slate-100 p-3 bg-white"
      >
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={t(
            'agent.creation.previewInputPlaceholder',
            '输入测试消息验证智能体效果...',
          )}
          disabled={isRunning || !draftId}
          className="h-9 text-xs"
        />
        <Button
          type="submit"
          size="sm"
          disabled={isRunning || !input.trim() || !draftId}
          className="h-9 w-9 p-0 bg-violet-600 hover:bg-violet-700 text-white shrink-0"
        >
          {isRunning ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Send className="h-3.5 w-3.5" />
          )}
        </Button>
      </form>
    </div>
  );
}
