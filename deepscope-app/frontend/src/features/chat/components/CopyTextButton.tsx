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
import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { cn } from '@/lib/utils';

export function CopyTextButton({ text, readText, className }: { text?: string; readText?: () => string; className?: string }) {
  const { t } = useTranslation();
  const [state, setState] = useState<'idle' | 'copied' | 'error'>('idle');
  useEffect(() => { if (state === 'idle') return; const timer = setTimeout(() => setState('idle'), 2000); return () => clearTimeout(timer); }, [state]);
  const label = t(state === 'copied' ? 'chat.copy.copied' : state === 'error' ? 'chat.copy.retry' : 'chat.copy.copy');
  async function copy() {
    try { await navigator.clipboard.writeText(readText ? readText() : text || ''); setState('copied'); } catch { setState('error'); }
  }
  return <button type="button" aria-label={label} title={label} onClick={() => void copy()}
    className={cn('inline-flex h-7 w-7 items-center justify-center rounded text-slate-500 hover:bg-slate-100 hover:text-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', state === 'error' && 'text-red-600', className)}>
    {state === 'copied' ? <Check aria-hidden className="h-3.5 w-3.5" /> : <Copy aria-hidden className="h-3.5 w-3.5" />}
  </button>;
}
