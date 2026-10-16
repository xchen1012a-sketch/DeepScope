/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useRef, useState, type SetStateAction } from 'react';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { draftKey, readDraft, removeDraft, writeDraft } from '@/lib/tabDraftStorage';

export function useTabDraft<T>(scope: string, initial: T) {
  const identity = useAccountIdentity();
  const key = draftKey(identity?.userId, scope);
  const [local, setLocal] = useState(() => ({ key, value: readDraft(key, initial) }));
  const value = local.key === key ? local.value : readDraft(key, initial);
  const latest = useRef({ key, value });
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  latest.current = { key, value };
  function setValue(next: SetStateAction<T>) {
    // Old network acknowledgements must not overwrite text typed after the request began.
    if (!mounted.current || latest.current.key !== key) return;
    const resolved = typeof next === 'function' ? (next as (previous: T) => T)(latest.current.value) : next;
    latest.current = { key, value: resolved };
    if (resolved === '') removeDraft(key);
    else writeDraft(key, resolved);
    setLocal({ key, value: resolved });
  }
  function clear() {
    if (!mounted.current || latest.current.key !== key) return;
    latest.current = { key, value: initial };
    removeDraft(key);
    setLocal({ key, value: initial });
  }
  return [value, setValue, clear] as const;
}
