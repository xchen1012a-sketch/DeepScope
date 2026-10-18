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

import { Suspense, lazy, useCallback, useEffect, useRef, useState, type ComponentType } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  BookMarked,
  Bot,
  Database,
  FileText,
  Trash2,
  Wrench,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input, Textarea } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { agentKeys, deleteAgent, getAgent, readAgentFile, updateAgent, type AgentDetail } from '@/api/agent';
import { can } from '@/api/auth';
import { createSession } from '@/api/advisor';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Copy, MoreHorizontal, Play } from 'lucide-react';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { agentCreationKeys, deleteDraft } from '@/api/agentCreation';
import { useAgentEditGuard, useAgentEditor } from './agentEditGuard';
import { cn } from '@/lib/utils';
import { BehaviorSection } from './components/BehaviorSection';
import { ToolSelectionSection } from './components/ToolSelectionSection';
import { SkillSelectionSection } from './components/SkillSelectionSection';
import { KnowledgeSelection } from '@/features/knowledge/KnowledgeSelection';

// 重型页签（文件树/编辑器/对话框）懒加载，保持详情页首屏 chunk 小；
// 页签内容按激活挂载，未激活页签的数据请求不会发出。
const SubagentsSection = lazy(() =>
  import('./components/SubagentsSection').then((module) => ({ default: module.SubagentsSection })),
);

type SectionId = 'behavior' | 'skills' | 'tools' | 'subagents' | 'knowledge';

/** Selection is owned by the management layout; this pane keeps the existing APIs. */
export default function AgentDetailPage() {
  const { agentId } = useParams<{ agentId: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const guard = useAgentEditGuard();
  const [section, setSection] = useState<SectionId>('behavior');
  const [focusedTab, setFocusedTab] = useState<SectionId>('behavior');
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [infoState, setInfoState] = useState({ dirty: false, valid: true });
  const infoSaveRef = useRef<(() => Promise<unknown>) | null>(null);
  const agentQuery = useQuery({ queryKey: agentKeys.agent(agentId ?? ''), queryFn: () => getAgent(agentId ?? ''), enabled: Boolean(agentId) });
  const behaviorFileQuery = useQuery({ queryKey: agentKeys.file(agentId ?? '', 'AGENTS.md'), queryFn: () => readAgentFile(agentId ?? '', 'AGENTS.md'), enabled: Boolean(agentId) });
  const start = useMutation({ mutationFn: () => createSession(agentId) });
  useEffect(() => { if (start.isSuccess && start.data) { const open = () => navigate(`/chat/${start.data.sessionId}`); if (guard) guard.request(open); else open(); } }, [start.isSuccess, start.data, guard, navigate]);
  useAgentEditor('start-session', { dirty: false, busy: start.isPending, save: async () => undefined, discard: () => undefined });
  const request = (action: () => void) => guard ? guard.request(action) : action();
  if (agentQuery.isError) return <div className="p-6 text-sm"><p role="alert" className="text-red-600">{t('agent.management.detailError')}</p><Button variant="outline" className="mt-3" onClick={() => void agentQuery.refetch()}>{t('agent.management.retry')}</Button></div>;
  if (!agentId) return <p className="p-6 text-sm text-slate-500">{t('agent.management.selectHint')}</p>;
  const agent = agentQuery.data;
  if (!agent) return <div className="space-y-5 p-6"><Skeleton className="h-9 w-56" /><Skeleton className="h-24" /><Skeleton className="h-64" /></div>;
  const readOnlyReason = agent.canEdit ? '' : t('agent.readonlyHintOther');
  const sections: Array<{ id: SectionId; label: string; icon: ComponentType<{ className?: string }> }> = [
    { id: 'behavior', label: t('agent.management.instructions'), icon: FileText },
    { id: 'tools', label: t('agent.sections.tools'), icon: Wrench },
    { id: 'skills', label: t('agent.sections.skills'), icon: BookMarked },
    { id: 'knowledge', label: t('agent.sections.knowledge'), icon: Database },
    { id: 'subagents', label: t('agent.sections.subagents'), icon: Bot },
  ];
  return <div className="mx-auto max-w-4xl space-y-6 px-5 py-5 sm:px-8">
    <header className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex min-w-0 items-center gap-2.5">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => request(() => navigate('/agent'))}
          className="h-8 w-8 p-0 text-slate-500 hover:text-slate-800"
          aria-label={t('agent.back')}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h2 className="break-words text-xl font-semibold text-slate-900">{agent.name}</h2>
        {agent.isPublic ? (
          <Badge tone="success">{t('agent.publicBadge')}</Badge>
        ) : (
          <Badge tone="default">{t('agent.privateBadge')}</Badge>
        )}
      </div>
      <div className="flex items-center gap-2"><Button size="sm" disabled={start.isPending || agent.enabled === false} onClick={() => request(() => start.mutate())}><Play className="h-3.5 w-3.5" />{t('agent.management.start')}</Button>
        <DropdownMenu.Root>
          <DropdownMenu.Trigger asChild>
            <Button size="icon" variant="ghost" aria-label={t('agent.management.more')}>
              <MoreHorizontal className="h-4 w-4 text-slate-500" />
            </Button>
          </DropdownMenu.Trigger>
          <DropdownMenu.Portal>
            <DropdownMenu.Content
              sideOffset={6}
              className="z-50 min-w-36 rounded-xl border border-slate-200/80 bg-white p-1 text-sm text-slate-700 shadow-xl ring-1 ring-slate-900/5 outline-none focus:outline-none focus-visible:outline-none"
            >
              <DropdownMenu.Item
                onSelect={() => request(() => navigate(`/agent/new?copyFrom=${encodeURIComponent(agent.agentId)}`))}
                className="flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs font-medium text-slate-700 outline-none transition-colors hover:bg-slate-100 data-[highlighted]:bg-slate-100 data-[highlighted]:text-slate-900 cursor-pointer"
              >
                <Copy className="h-3.5 w-3.5 text-slate-400" />
                <span>{t('agent.copyAction')}</span>
              </DropdownMenu.Item>
              {agent.canEdit ? (
                <DropdownMenu.Item
                  onSelect={() => setDeleteOpen(true)}
                  className="flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs font-medium text-red-600 outline-none transition-colors hover:bg-red-50 data-[highlighted]:bg-red-50 data-[highlighted]:text-red-700 cursor-pointer"
                >
                  <Trash2 className="h-3.5 w-3.5 text-red-500" />
                  <span>{t('agent.delete')}</span>
                </DropdownMenu.Item>
              ) : null}
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        </DropdownMenu.Root>
      </div>
    </header>
    {start.isError ? <p role="alert" className="text-sm text-red-600">{t('agent.management.startFailed')}</p> : null}
    <div role="tablist" aria-label={t('agent.management.settingsTabs')} className="flex gap-1 overflow-x-auto pb-1" onKeyDown={event => {
      const tabs = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
      const index = tabs.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1;
      if (next >= 0) { event.preventDefault(); tabs[next]?.focus(); }
    }}>{sections.map(item => <button key={item.id} type="button" role="tab" tabIndex={focusedTab === item.id ? 0 : -1} onFocus={() => setFocusedTab(item.id)} id={`agent-tab-${item.id}`} aria-selected={section === item.id} aria-controls={`agent-panel-${item.id}`} onClick={() => { if (section !== item.id) setSection(item.id); }} className={cn('flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-2 text-sm text-slate-500 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300', section === item.id && 'bg-indigo-50 text-indigo-700')}><item.icon aria-hidden="true" className="h-4 w-4" />{item.label}</button>)}</div>
    <div role="tabpanel" id={`agent-panel-${section}`} aria-labelledby={`agent-tab-${section}`}>
      <div className={cn(section === 'behavior' ? 'block space-y-5' : 'hidden')} aria-hidden={section !== 'behavior'}>
        <AgentInfoCard
          agent={agent}
          onStateChange={setInfoState}
          saveRef={infoSaveRef}
        />
        <BehaviorSection
          key={agent.agentId}
          agentId={agent.agentId}
          canEdit={agent.canEdit}
          readOnlyReason={readOnlyReason}
          fileQuery={behaviorFileQuery}
          compact
          extraDirty={infoState.dirty}
          extraValid={infoState.valid}
          onSaveExtra={() => (infoSaveRef.current ? infoSaveRef.current() : Promise.resolve())}
        />
      </div>
      <div className={cn(section === 'skills' ? 'block' : 'hidden')} aria-hidden={section !== 'skills'}>
        <SkillSelectionSection agentId={agent.agentId} canEdit={agent.canEdit} />
      </div>
      <div className={cn(section === 'tools' ? 'block' : 'hidden')} aria-hidden={section !== 'tools'}>
        <ToolSelectionSection agentId={agent.agentId} canEdit={agent.canEdit} />
      </div>
      <div className={cn(section === 'knowledge' ? 'block' : 'hidden')} aria-hidden={section !== 'knowledge'}>
        <KnowledgeSelection agentId={agent.agentId} canEdit={agent.canEdit} />
      </div>
      <div className={cn(section === 'subagents' ? 'block' : 'hidden')} aria-hidden={section !== 'subagents'}>
        <Suspense fallback={<SectionSkeleton />}>
          <SubagentsSection agentId={agent.agentId} subagents={agent.sections.subagents} canEdit={agent.canEdit} readOnlyReason={readOnlyReason} />
        </Suspense>
      </div>
    </div>
    <DeleteAgentDialog agent={agent} open={deleteOpen} onClose={() => setDeleteOpen(false)} onDeleted={() => navigate('/agent')} />
  </div>;
}

/** 懒加载页签 chunk 载入期间的区块骨架。 */
function SectionSkeleton() {
  return (
    <div className="space-y-3">
      <Skeleton className="h-5 w-40" />
      <Skeleton className="h-3 w-3/4" />
      <Skeleton className="h-64 w-full rounded-2xl" />
    </div>
  );
}

function AgentInfoCard({
  agent,
  onStateChange,
  saveRef,
}: {
  agent: AgentDetail;
  onStateChange?: (state: { dirty: boolean; valid: boolean }) => void;
  saveRef?: React.MutableRefObject<(() => Promise<unknown>) | null>;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description);
  const [isPublic, setIsPublic] = useState(agent.isPublic ?? false);
  const canShare = can('agent.share') || can('agent.manage');
  const valid = Boolean(name.trim());
  const dirty =
    agent.canEdit &&
    (name !== agent.name ||
      description !== agent.description ||
      (canShare && isPublic !== (agent.isPublic ?? false)));

  useEffect(() => {
    onStateChange?.({ dirty, valid });
  }, [dirty, valid, onStateChange]);

  useEffect(() => {
    if (!dirty) {
      setName(agent.name);
      setDescription(agent.description);
      setIsPublic(agent.isPublic ?? false);
    }
  }, [agent.name, agent.description, agent.isPublic, dirty]);

  const updateMutation = useMutation({
    mutationFn: (input: { name: string; description: string; isPublic?: boolean }) =>
      updateAgent(agent.agentId, input),
    onSuccess: (result) => {
      setName(result.name);
      setDescription(result.description);
      setIsPublic(result.isPublic ?? false);
      queryClient.setQueryData(agentKeys.agent(agent.agentId), result);
      queryClient.invalidateQueries({ queryKey: agentKeys.agents });
    },
  });

  const save = useCallback(async () => {
    if (!name.trim()) throw new Error('Invalid name');
    return updateMutation.mutateAsync({
      name: name.trim(),
      description: description.trim(),
      ...(canShare && isPublic !== (agent.isPublic ?? false) ? { isPublic } : {}),
    });
  }, [name, description, canShare, isPublic, agent.isPublic, updateMutation]);

  useEffect(() => {
    if (saveRef) {
      saveRef.current = save;
    }
  }, [saveRef, save]);

  useAgentEditor('agent-info', {
    dirty,
    busy: updateMutation.isPending,
    save,
    discard: () => {
      setName(agent.name);
      setDescription(agent.description);
      setIsPublic(agent.isPublic ?? false);
    },
  });

  return (
    <section className="space-y-3">
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className="mb-1.5 block text-xs font-medium text-slate-600" htmlFor="agent-info-name">
            {t('agent.field.name')}
          </label>
          <Input
            id="agent-info-name"
            value={name}
            maxLength={120}
            disabled={!agent.canEdit || updateMutation.isPending}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-medium text-slate-600" htmlFor="agent-info-description">
            {t('agent.field.description')}
          </label>
          <Textarea
            id="agent-info-description"
            value={description}
            maxLength={400}
            disabled={!agent.canEdit || updateMutation.isPending}
            rows={1}
            className="min-h-10 py-2"
            onChange={(event) => setDescription(event.target.value)}
          />
        </div>
      </div>
      {agent.canEdit && canShare ? (
        <div className="space-y-1 pt-1">
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={isPublic}
              disabled={updateMutation.isPending}
              onChange={(e) => setIsPublic(e.target.checked)}
            />
            {t('agent.shareWithAll')}
          </label>
          <p className="text-xs leading-5 text-slate-500">{t('agent.shareWithAllHelp')}</p>
        </div>
      ) : null}
      {!agent.canEdit ? <p className="text-xs text-amber-700">{t('agent.readonlyHintOther')}</p> : null}
      {updateMutation.isError ? <p role="alert" className="text-sm text-red-600">{t('agent.management.saveFailed')}</p> : null}
    </section>
  );
}

/** 只读清单页签（技能/工具/知识库）。 */
function ReadonlyListSection({
  title,
  hint,
  emptyHint,
  items,
  icon: Icon,
  mono,
}: {
  title: string;
  hint: string;
  emptyHint: string;
  items: Array<{ key: string; title: string; subtitle: string }>;
  icon: ComponentType<{ className?: string }>;
  mono?: boolean;
}) {
  return (
    <section>
      <div className="mb-4">
        <h2 className="text-[18px] font-semibold tracking-tight text-slate-900">{title}</h2>
        <p className="mt-1.5 text-[13px] leading-relaxed text-slate-500">{hint}</p>
      </div>
      {items.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-slate-300 bg-muted/40 px-4 py-6 text-center text-[13px] text-slate-500">
          {emptyHint}
        </p>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-border bg-white">
          {items.map((item) => (
            <div key={item.key} className="flex items-start gap-3 border-b border-border/70 px-4 py-3 last:border-0">
              <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-indigo-50">
                {mono ? <FileText className="h-3.5 w-3.5 text-indigo-600" /> : <Icon className="h-3.5 w-3.5 text-indigo-600" />}
              </span>
              <div className="min-w-0 flex-1">
                <span
                  className={cn(
                    'break-all text-[13.5px] font-medium text-slate-800',
                    mono && 'font-mono text-[12.5px]',
                  )}
                >
                  {item.title}
                </span>
                {item.subtitle ? (
                  <p className="mt-0.5 text-[12.5px] leading-relaxed text-slate-500">{item.subtitle}</p>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export function DeleteAgentDialog({
  agent,
  open,
  onClose,
  onDeleted,
  draft = false,
}: {
  agent: Pick<AgentDetail, 'agentId' | 'name'>;
  open: boolean;
  onClose: () => void;
  onDeleted: () => void;
  draft?: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const deleteMutation = useMutation({
    mutationFn: () => draft ? deleteDraft(agent.agentId) : deleteAgent(agent.agentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.agents });
      queryClient.invalidateQueries({ queryKey: agentCreationKeys.drafts });
      onDeleted();
    },
  });

  return (
    <ConfirmDeleteDialog
      open={open}
      title={t('agent.deleteTitle')}
      targetName={agent.name}
      description={t(draft ? 'agent.management.deleteDraftHint' : 'agent.deleteBody')}
      confirmText={t('agent.delete')}
      isDeleting={deleteMutation.isPending}
      onClose={onClose}
      onConfirm={() => deleteMutation.mutate()}
    >
      {deleteMutation.isError ? <p role="alert" className="text-sm text-destructive">{t('agent.management.deleteFailed')}</p> : null}
    </ConfirmDeleteDialog>
  );
}
