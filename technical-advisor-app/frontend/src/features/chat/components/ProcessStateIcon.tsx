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

import { useContext } from 'react';
import { ProcessMotionContext } from './processMotion';
import { BrainCircuit, CircleAlert, Wrench } from 'lucide-react';

/** Decoration only: the row's text and accessible status explain what is happening. */
export function ProcessStateIcon({ state, kind = 'tool', animate = true }: {
  state: 'running' | 'done' | 'failed'; kind?: 'thinking' | 'tool'; animate?: boolean;
}) {
  const enabled = useContext(ProcessMotionContext) && animate;
  if (state === 'running') return <span aria-hidden="true" data-process-loading={enabled || undefined}
    className={`inline-block h-2 w-2 mx-[3px] shrink-0 rounded-full bg-slate-500 ${enabled ? 'process-status-dot' : ''}`} />;
  const Icon = state === 'failed' ? CircleAlert : kind === 'thinking' ? BrainCircuit : Wrench;
  return <Icon aria-hidden="true" strokeWidth={1.5} className={`h-3.5 w-3.5 shrink-0 ${state === 'failed' ? 'text-red-500' : 'text-slate-400'}`} />;
}
