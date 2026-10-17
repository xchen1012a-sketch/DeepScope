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

import { useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { UNSAFE_DataRouterContext, useBlocker } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { AgentEditContext, type AgentEditor } from './agentEditGuard';

export function AgentEditProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const dataRouter = useContext(UNSAFE_DataRouterContext);
  const approved = useRef(false);
  const resetBlocked = useRef<(() => void) | null>(null);
  const editors = useRef(new Map<string, () => AgentEditor>());
  const [action, setAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(false);
  const [, render] = useState(0);
  const changed = useCallback(() => render(value => value + 1), []);
  const register = useCallback((key: string, editor: () => AgentEditor) => {
    editors.current.set(key, editor);
    return () => { if (editors.current.get(key) === editor) editors.current.delete(key); };
  }, []);
  const request = useCallback((next: () => void) => {
    if ([...editors.current.values()].some(get => get().dirty || get().busy)) {
      setError(false);
      setAction(() => next);
    } else next();
  }, []);
  const context = useMemo(() => ({ register, changed, request }), [register, changed, request]);
  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => {
      if ([...editors.current.values()].some(get => get().dirty || get().busy)) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', unload);
    return () => window.removeEventListener('beforeunload', unload);
  }, []);
  const shouldBlock = useCallback(() => !approved.current && [...editors.current.values()].some(get => get().dirty || get().busy), []);
  const blocked = useCallback((proceed: () => void, reset: () => void) => { resetBlocked.current = reset; setError(false); setAction(() => proceed); }, []);
  const stay = () => { resetBlocked.current?.(); resetBlocked.current = null; setAction(null); };
  const commit = () => {
    const next = action;
    const alreadyBlocked = resetBlocked.current !== null;
    resetBlocked.current = null;
    setAction(null);
    approved.current = !alreadyBlocked;
    try { next?.(); } finally { approved.current = false; }
  };
  const busy = [...editors.current.values()].some(get => get().busy);
  const proceed = async () => {
    setSaving(true); setError(false);
    try {
      for (const get of editors.current.values()) if (get().dirty) await get().save();
      commit();
    } catch { setError(true); }
    finally { setSaving(false); }
  };
  return (
    <AgentEditContext.Provider value={context}>
      {dataRouter ? <AgentRouteBlocker shouldBlock={shouldBlock} onBlocked={blocked} /> : null}
      {children}
      <Dialog open={action !== null} onOpenChange={(open) => { if (!open && !saving) stay(); }}>
        <DialogContent
          size="sm"
          className="max-w-[460px] rounded-2xl p-6 sm:p-7"
        >
          <div className="space-y-3">
            <DialogTitle className="text-lg font-semibold tracking-tight text-slate-900 pr-8">
              {t('agent.management.unsavedTitle')}
            </DialogTitle>
            <div className="text-sm leading-relaxed text-slate-600">
              {t(busy ? 'agent.management.waitForOperation' : 'agent.management.unsavedHint')}
            </div>
            {error ? (
              <p role="alert" className="text-xs text-red-600 mt-2">
                {t('agent.management.saveFailed')}
              </p>
            ) : null}
          </div>

          <div className="mt-6 flex flex-wrap items-center justify-end gap-2.5">
            <Button
              type="button"
              variant="outline"
              className="rounded-full px-4 h-9 text-xs font-medium text-slate-700 border-slate-200 hover:bg-slate-100"
              disabled={saving}
              onClick={stay}
            >
              {t('agent.management.keepEditing')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              className="rounded-full px-4 h-9 text-xs font-medium bg-red-600 hover:bg-red-700 text-white shadow-sm"
              disabled={saving || busy}
              onClick={() => {
                for (const get of editors.current.values()) get().discard();
                commit();
              }}
            >
              {t('agent.management.discardSwitch')}
            </Button>
            <Button
              type="button"
              variant="default"
              className="rounded-full px-5 h-9 text-xs font-medium bg-primary hover:bg-primary/90 text-white shadow-sm"
              disabled={saving || busy}
              onClick={() => void proceed()}
            >
              {saving ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : null}
              {t('agent.management.saveSwitch')}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </AgentEditContext.Provider>
  );
}

function AgentRouteBlocker({ shouldBlock, onBlocked }: { shouldBlock: () => boolean; onBlocked: (proceed: () => void, reset: () => void) => void }) {
  const blocker = useBlocker(shouldBlock);
  useEffect(() => { if (blocker.state === 'blocked') onBlocked(blocker.proceed, blocker.reset); }, [blocker, onBlocked]);
  return null;
}
