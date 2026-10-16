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
import { useTranslation } from 'react-i18next';
import { useId, useState } from 'react';
import { CheckCircle2, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

export interface PermissionNode {
  id: string;
  name: string;
  nameEn?: string;
  children: PermissionNode[];
}

function leafIds(node: PermissionNode): string[] {
  return node.children.length ? node.children.flatMap(leafIds) : [node.id];
}

/** Native checkboxes retain keyboard behavior while parent nodes reflect leaf selections. */
type PermissionTreeProps = {
  nodes: PermissionNode[]; value: string[]; disabled?: boolean; disabledIds?: string[];
} & ({ readOnly: true; onChange?: never } | { readOnly?: false; onChange: (value: string[]) => void });

export function PermissionTree({ nodes, value, onChange, disabled = false, disabledIds = [], readOnly = false }: PermissionTreeProps) {
  const { t, i18n } = useTranslation();
  const groupId = useId();
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  return <ul className="space-y-3">
    {nodes.map(node => {
      const ids = leafIds(node);
      const count = ids.filter(id => value.includes(id)).length;
      const mixed = count > 0 && count < ids.length;
      const label = i18n.language.startsWith('en') ? node.nameEn || node.name : node.name;
      const open = expanded.has(node.id);
      if (readOnly) return <li key={node.id} className="min-w-0">
        {node.children.length ? <>
          <Button variant="ghost" className="min-h-11 w-full justify-start gap-2 px-2" aria-label={label}
            aria-expanded={open} aria-controls={groupId + node.id}
            onClick={() => setExpanded(current => { const next = new Set(current); if (next.has(node.id)) next.delete(node.id); else next.add(node.id); return next; })}>
            <ChevronRight aria-hidden className={'h-4 w-4 shrink-0 transition-transform motion-reduce:transition-none ' + (open ? 'rotate-90' : '')} />
            <span className="min-w-0 flex-1 truncate text-left">{label}</span>
            <Badge tone="success" aria-label={t('account.grantedCount', { count })}>{count}</Badge>
          </Button>
          {open ? <div id={groupId + node.id} className="ml-4 min-w-0 pl-4">
            <PermissionTree nodes={node.children} value={value} readOnly />
          </div> : null}
        </> : <div className="flex min-h-10 min-w-0 items-center gap-2 py-2 text-sm">
          <CheckCircle2 aria-hidden className="h-3.5 w-3.5 shrink-0 text-emerald-600" />
          <span className="min-w-0 flex-1 break-words">{label}</span>
          <span className="shrink-0 text-xs text-muted-foreground">{t('account.granted')}</span>
        </div>}
      </li>;
      return <li key={node.id} className="space-y-2">
        <div className="flex items-center gap-1">
          {node.children.length ? <button type="button" aria-label={t(open ? 'access.collapsePermission' : 'access.expandPermission', { name: label })}
            aria-expanded={open} aria-controls={groupId + node.id}
            onClick={() => setExpanded(current => { const next = new Set(current); if (next.has(node.id)) next.delete(node.id); else next.add(node.id); return next; })}
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded text-slate-500 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-200">
            <ChevronRight aria-hidden className={'h-3.5 w-3.5 transition-transform ' + (open ? 'rotate-90' : '')} />
          </button> : <span aria-hidden="true" className="h-7 w-7 shrink-0" />}
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" className="h-4 w-4 accent-violet-500" disabled={disabled || disabledIds.includes(node.id)}
            ref={element => { if (element) element.indeterminate = mixed; }}
            checked={count === ids.length} aria-checked={mixed ? 'mixed' : count === ids.length}
            onChange={event => onChange?.(event.target.checked ? [...new Set([...value, ...ids])] : value.filter(id => !ids.includes(id)))} />
          <span className={node.children.length ? 'font-medium' : undefined}>{label}</span>
        </label>
        </div>
        {node.children.length && open ? <div id={groupId + node.id} className="ml-3 border-l border-slate-100 pl-5">
          <PermissionTree nodes={node.children} value={value} onChange={onChange!} disabled={disabled} disabledIds={disabledIds} />
        </div> : null}
      </li>;
    })}
  </ul>;
}
