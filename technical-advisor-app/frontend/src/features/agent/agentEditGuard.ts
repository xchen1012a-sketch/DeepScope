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

import { createContext, useContext, useEffect, useRef } from 'react';

export interface AgentEditor { dirty: boolean; busy?: boolean; save: () => Promise<unknown>; discard: () => void }
export interface AgentEditGuard {
  register: (key: string, editor: () => AgentEditor) => () => void;
  changed: () => void;
  request: (action: () => void) => void;
}
export const AgentEditContext = createContext<AgentEditGuard | null>(null);
export function useAgentEditGuard() { return useContext(AgentEditContext); }
export function useAgentEditor(key: string, editor: AgentEditor | (() => AgentEditor)) {
  const guard = useAgentEditGuard();
  const latest = useRef(editor);
  latest.current = editor;
  useEffect(() => guard?.register(key, () => (typeof latest.current === 'function' ? latest.current() : latest.current)), [guard, key]);
  const current = typeof editor === 'function' ? editor() : editor;
  useEffect(() => { guard?.changed(); }, [guard, current.dirty, current.busy]);
}
