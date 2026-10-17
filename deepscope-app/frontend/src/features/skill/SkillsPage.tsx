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

import { listQuery, selectionQuery, type SelectionContext } from '@/api/listQuery';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Archive,
  CheckSquare,
  FileText,
  Loader2,
  Plus,
  Square,
  Trash2,
  UploadCloud,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Page } from '@/components/Page';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { usePagedList } from '@/components/usePagedList';
import { TableToolbar } from '@/components/TableToolbar';
import { TableActions } from '@/components/TableActions';
import { DataTable } from '@/components/DataTable';
import { AppSelect } from '@/components/ui/select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { ApiError, api, apiFetch } from '@/lib/apiClient';
import i18n from '@/i18n';
import { formatBytes } from '@/lib/format';
import { cn } from '@/lib/utils';
import { fileFromClipboard, firstFile } from './skillDrop';

export interface SkillSummary {
  skillId: string;
  name: string;
  description: string;
  status: 'draft' | 'published';
  origin: 'personal';
  canEdit: boolean;
  agents: string[];
}

export interface SkillDetail extends SkillSummary {
  markdown?: string;
}

export interface ParsedSkillItem {
  name: string;
  description: string;
  markdown: string;
  resourceCount?: number;
}

export interface ParseSkillResponse {
  batch: boolean;
  count: number;
  skills: ParsedSkillItem[];
}

export const skillKeys = { skills: ['advisor', 'skills'] as const };

export function listSkills(query = '', selection?: SelectionContext): Promise<SkillSummary[]> {
  return api.get<SkillSummary[]>(selection ? selectionQuery('/api/advisor/skills', selection) : listQuery('/api/advisor/skills', query));
}

export function getSkill(skillId: string): Promise<SkillDetail> {
  return api.get<SkillDetail>(`/api/advisor/skills/${encodeURIComponent(skillId)}`);
}

export function updateSkill(
  skillId: string,
  data: { name: string; description: string; content?: string },
): Promise<SkillSummary> {
  return apiFetch<SkillSummary>(`/api/advisor/skills/${encodeURIComponent(skillId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export default function SkillsPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const skillsQuery = usePagedList<SkillSummary>(skillKeys.skills, '/api/advisor/skills', { filters: { status: '' } });
  const search = skillsQuery.params.q;
  const [searchDraft, setSearchDraft] = useState('');
  const statusFilter = String(skillsQuery.params.status ?? '');
  const [open, setOpen] = useState(false);
  const [editingSkill, setEditingSkill] = useState<SkillSummary | null>(null);
  const [skillToDelete, setSkillToDelete] = useState<SkillSummary | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: skillKeys.skills });

  const deleteMutation = useMutation({
    mutationFn: (skillId: string) =>
      apiFetch<void>(`/api/advisor/skills/${encodeURIComponent(skillId)}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      setSkillToDelete(null);
      refresh();
    },
  });

  const statusOptions = [
    { value: '', label: t('skill.statusAll', '全部') },
    { value: 'published', label: t('skill.published', '已发布') },
    { value: 'draft', label: t('skill.draft', '草稿') },
  ];

  return (
    <Page>
      <TableToolbar
        search={searchDraft} onSearchChange={setSearchDraft}
        onSearch={() => skillsQuery.submit(searchDraft)}
        placeholder={t('skill.searchPlaceholder')}
        filters={<AppSelect
          size="md" value={statusFilter}
          onChange={value => skillsQuery.setFilters({ status: value })}
          options={statusOptions} allowClear
          onClear={() => skillsQuery.setFilters({ status: '' })}
          placeholder={t('skill.statusFilterPlaceholder')} className="w-28 text-sm"
        />}
        actions={<Button onClick={() => setOpen(true)}><Plus className="h-4 w-4" />{t('skill.new')}</Button>}
      />

      <DataTable
        columns={[
          {
            id: 'name',
            header: t('skill.columnName'),
            headerClassName: 'py-3.5 pl-4 pr-3 sm:pl-6',
            cell: () => null,
          },
          {
            id: 'description',
            header: t('skill.columnDescription'),
            headerClassName: 'px-3 py-3.5 hidden md:table-cell',
            cell: () => null,
          },
          {
            id: 'agents',
            header: t('skill.columnAgents'),
            headerClassName: 'px-3 py-3.5',
            cell: () => null,
          },
          {
            id: 'status',
            header: t('skill.columnStatus'),
            headerClassName: 'px-3 py-3.5',
            cell: () => null,
          },
          {
            id: 'actions',
            header: t('skill.columnActions'),
            headerClassName: 'py-3.5 pl-3 pr-4 text-right sm:pr-6',
            cell: () => null,
          },
        ]}
        data={skillsQuery.data?.items ?? []}
        keyExtractor={(skill) => skill.skillId}
        renderRow={(skill) => (
          <SkillTableRow
            key={skill.skillId}
            skill={skill}
            onEdit={() => setEditingSkill(skill)}
            onDeleteDirect={(s) => deleteMutation.mutate(s.skillId)}
            onRequestDelete={(s) => setSkillToDelete(s)}
            onChanged={refresh}
          />
        )}
        isLoading={skillsQuery.isPending}
        error={skillsQuery.isError ? t('table.loadError') : undefined}
        onRetry={() => { void skillsQuery.refetch(); }}
        emptyTitle={t(search || statusFilter ? 'table.noResults' : 'skill.empty')}
        emptyAction={search || statusFilter ? <Button variant="outline" onClick={() => { skillsQuery.reset(); setSearchDraft(''); }}>{t('table.clearFilters')}</Button> : undefined}
        page={skillsQuery.data?.page ?? skillsQuery.params.page}
        pageSize={skillsQuery.params.size}
        total={skillsQuery.data?.total ?? 0}
        onPageChange={skillsQuery.setPage}
        pageSizeOptions={[10, 20, 50]}
        onPageSizeChange={skillsQuery.setPageSize}
      />

      <DraftDialog open={open} onClose={() => setOpen(false)} onCreated={refresh} />
      {editingSkill ? (
        <EditSkillDialog
          skill={editingSkill}
          open={editingSkill != null}
          onClose={() => setEditingSkill(null)}
          onUpdated={() => {
            setEditingSkill(null);
            refresh();
          }}
        />
      ) : null}
      <ConfirmDeleteDialog
        open={skillToDelete != null}
        title={
          skillToDelete
            ? t('skill.deleteConfirmTitleWithName', {
                defaultValue: `删除“${skillToDelete.name}”？`,
                name: skillToDelete.name,
              })
            : ''
        }
        targetName={skillToDelete?.name ?? ''}
        description={
          skillToDelete
            ? t('skill.deleteConfirmDesc', {
                defaultValue: `这将永久删除“${skillToDelete.name}”。此操作无法撤销。`,
                name: skillToDelete.name,
              })
            : undefined
        }
        isDeleting={deleteMutation.isPending}
        onClose={() => setSkillToDelete(null)}
        onConfirm={() => {
          if (skillToDelete) {
            deleteMutation.mutate(skillToDelete.skillId);
          }
        }}
      />
    </Page>
  );
}

function SkillTableRow({
  skill,
  onEdit,
  onDeleteDirect,
  onRequestDelete,
  onChanged,
}: {
  skill: SkillSummary;
  onEdit: () => void;
  onDeleteDirect: (skill: SkillSummary) => void;
  onRequestDelete: (skill: SkillSummary) => void;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const publish = useMutation({
    mutationFn: () =>
      apiFetch<void>(`/api/advisor/skills/${encodeURIComponent(skill.skillId)}/publish`, {
        method: 'POST',
      }),
    onSuccess: onChanged,
  });
  const unpublish = useMutation({
    mutationFn: () =>
      apiFetch<void>(`/api/advisor/skills/${encodeURIComponent(skill.skillId)}/unpublish`, {
        method: 'POST',
      }),
    onSuccess: onChanged,
  });

  const pending = publish.isPending || unpublish.isPending;

  return (
    <tr className="hover:bg-slate-50/60 transition-colors">
      <td className="py-3.5 pl-4 pr-3 sm:pl-6">
        <div className="min-w-0">
          <span className="font-semibold text-slate-900 block truncate" title={skill.name}>
            {skill.name}
          </span>
          <span className="text-xs text-slate-400 md:hidden line-clamp-1 mt-0.5">
            {skill.description}
          </span>
        </div>
      </td>
      <td className="px-3 py-3.5 hidden md:table-cell max-w-md">
        {skill.description ? (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <p className="text-xs text-slate-500 line-clamp-2 leading-relaxed cursor-default">
                  {skill.description}
                </p>
              </TooltipTrigger>
              <TooltipContent className="max-w-md whitespace-pre-wrap text-xs">
                {skill.description}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          <span className="text-xs text-slate-400">-</span>
        )}
      </td>
      <td className="px-3 py-3.5 max-w-[200px]">
        {skill.agents && skill.agents.length > 0 ? (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="block truncate text-xs text-slate-700 cursor-default">
                  {skill.agents.join('、')}
                </span>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs text-xs">
                <span className="block leading-relaxed">{skill.agents.join('、')}</span>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          <span className="text-xs text-slate-400">{t('skill.noAgents')}</span>
        )}
      </td>
      <td className="px-3 py-3.5 whitespace-nowrap">
        <Badge
          tone={skill.status === 'published' ? 'success' : 'default'}
          className="text-xs"
        >
          {t(skill.status === 'published' ? 'skill.published' : 'skill.draft')}
        </Badge>
      </td>
      <td className="py-3.5 pl-3 pr-4 text-right sm:pr-6 whitespace-nowrap">
        <TableActions actions={[
          { id: 'edit', label: t('skill.edit'), hidden: !skill.canEdit, disabled: pending, onClick: onEdit },
          { id: 'publish', label: t('skill.publish'), hidden: !skill.canEdit || skill.status !== 'draft', disabled: pending, loading: publish.isPending, onClick: () => publish.mutate() },
          { id: 'unpublish', label: t('skill.unpublish'), hidden: !skill.canEdit || skill.status !== 'published', disabled: pending, loading: unpublish.isPending, onClick: () => unpublish.mutate() },
          { id: 'delete', label: t('skill.delete'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: !skill.canEdit, disabled: pending, onClick: () => { if (skill.status === 'draft') onDeleteDirect(skill); else onRequestDelete(skill); } },
        ]} />
      </td>
    </tr>
  );
}

function extractErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.body) {
    try {
      const parsed = JSON.parse(error.body);
      if (typeof parsed?.messageKey === 'string' && i18n.exists(parsed.messageKey)) return i18n.t(parsed.messageKey);
      if (parsed?.code === 'invalid_update' && typeof parsed.message === 'string') {
        const key = ['skill', 'errors', parsed.message].join('.');
        if (i18n.exists(key)) return i18n.t(key);
      }
      if (typeof parsed?.message === 'string' && parsed.message.trim()) {
        return parsed.message.trim();
      }
    } catch {
      return error.body;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

function SkillFileCard({
  file,
  onReselect,
  onRemove,
}: {
  file: File;
  onReselect: () => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const lower = file.name.toLowerCase();
  const isZip = lower.endsWith('.zip') || lower.endsWith('.skill');

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50/80 p-4 transition-all">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-white border border-slate-200/80 shadow-sm">
            {isZip ? (
              <Archive className="h-5 w-5 text-indigo-500" />
            ) : (
              <FileText className="h-5 w-5 text-emerald-500" />
            )}
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="truncate text-sm font-semibold text-slate-800" title={file.name}>
                {file.name}
              </span>
              <Badge tone="success" className="text-xs shrink-0">
                {t('skill.fileReady')}
              </Badge>
            </div>
            <p className="mt-0.5 text-xs text-slate-400">{formatBytes(file.size)}</p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-8 text-xs font-medium"
            onClick={onReselect}
          >
            {t('skill.reselect')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="h-8 text-xs text-slate-500 hover:text-red-600"
            onClick={onRemove}
          >
            {t('skill.removeFile')}
          </Button>
        </div>
      </div>
    </div>
  );
}

function EditSkillDialog({
  skill,
  open,
  onClose,
  onUpdated,
}: {
  skill: SkillSummary;
  open: boolean;
  onClose: () => void;
  onUpdated: () => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState(skill.name);
  const [brief, setBrief] = useState(skill.description);
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    setLoading(true);
    getSkill(skill.skillId)
      .then((detail) => {
        if (!active) return;
        setName(detail.name || skill.name);
        setBrief(detail.description || skill.description);
        setContent(detail.markdown || '');
      })
      .catch(() => {
        if (!active) return;
        setName(skill.name);
        setBrief(skill.description);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [skill.skillId]);

  const updateMutation = useMutation({
    mutationFn: () =>
      updateSkill(skill.skillId, {
        name: name.trim(),
        description: brief.trim(),
        content: content.trim(),
      }),
    onSuccess: onUpdated,
  });

  const draftAi = useMutation({
    mutationFn: () =>
      apiFetch<{ content?: string; instructions?: string; name?: string; description?: string }>(
        '/api/advisor/skills/draft-content',
        {
          method: 'POST',
          body: JSON.stringify({
            name: name.trim(),
            description: brief.trim(),
          }),
        },
      ),
    onSuccess: (data) => {
      if (data.instructions?.trim()) {
        setContent(data.instructions.trim());
      } else if (data.content?.trim()) {
        setContent(data.content.trim());
      }
      if (!name.trim() && data.name?.trim()) {
        setName(data.name.trim());
      }
    },
  });

  const handleInsertTemplate = () => {
    const title = name.trim() || '技能名称';
    const desc = brief.trim() || '在此描述技能的适用场景与触发条件...';
    const template = `# ${title}

## 适用场景
${desc}

## 执行步骤
1. 第一步：分析需求与上下文
2. 第二步：执行核心业务逻辑
3. 第三步：验证结果并输出结构化反馈

## 约束与规范
- 严格遵循输入输出规范
- 避免生成虚假或未经验证的内容
`;
    setContent(template);
  };

  const pending = updateMutation.isPending || draftAi.isPending || loading;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && !pending && onClose()}>
      <DialogContent size="lg">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>{t('skill.editTitle')}</DialogTitle>
            <Badge
              tone={skill.status === 'published' ? 'success' : 'default'}
              className="text-xs"
            >
              {t(skill.status === 'published' ? 'skill.published' : 'skill.draft')}
            </Badge>
          </div>
        </DialogHeader>
        <DialogBody className="space-y-4">
          {loading ? (
            <div className="flex h-48 items-center justify-center">
              <Loader2 className="h-6 w-6 animate-spin text-slate-400" />
            </div>
          ) : (
            <div className="space-y-4">
              <label className="block space-y-1.5 text-sm text-slate-700">
                <span className="font-medium">{t('skill.name')}</span>
                <Input
                  value={name}
                  placeholder="e.g. code_review"
                  onChange={(event) => setName(event.target.value)}
                />
              </label>

              <label className="block space-y-1.5 text-sm text-slate-700">
                <span className="font-medium">{t('skill.brief')}</span>
                <Textarea
                  value={brief}
                  rows={2}
                  placeholder={t('skill.briefPlaceholder')}
                  onChange={(event) => setBrief(event.target.value)}
                />
              </label>

              <div className="space-y-1.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-medium text-slate-700">
                    {t('skill.content')}
                  </span>
                  <div className="flex items-center gap-1.5">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs font-medium text-amber-700 hover:text-amber-800"
                      disabled={pending || !brief.trim()}
                      onClick={() => draftAi.mutate()}
                    >
                      {draftAi.isPending ? (
                        <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                      ) : null}
                      {t('skill.draftAi')}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      className="h-7 text-xs text-slate-600 hover:text-slate-900"
                      disabled={pending}
                      onClick={handleInsertTemplate}
                    >
                      {t('skill.insertTemplate')}
                    </Button>
                  </div>
                </div>
                <Textarea
                  value={content}
                  rows={8}
                  placeholder={t('skill.contentPlaceholder')}
                  className="font-mono text-xs leading-relaxed"
                  onChange={(event) => setContent(event.target.value)}
                />
              </div>

              {updateMutation.isError ? (
                <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                  {extractErrorMessage(updateMutation.error, t('skill.error'))}
                </div>
              ) : null}
            </div>
          )}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={pending}>
            {t('chat.cancel')}
          </Button>
          <Button
            disabled={!name.trim() || !brief.trim() || pending}
            onClick={() => updateMutation.mutate()}
          >
            {updateMutation.isPending ? (
              <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
            ) : null}
            {t('skill.saveChanges')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DraftDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<'upload' | 'manual'>('upload');
  const [uploadStep, setUploadStep] = useState<'select' | 'preview'>('select');

  // File selection & parse
  const [file, setFile] = useState<File | null>(null);
  const [over, setOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Parsed preview states
  const [parsedSkills, setParsedSkills] = useState<ParsedSkillItem[]>([]);
  const [singleName, setSingleName] = useState('');
  const [singleBrief, setSingleBrief] = useState('');
  const [singleContent, setSingleContent] = useState('');
  const [selectedIndices, setSelectedIndices] = useState<Set<number>>(new Set());

  // Manual mode states
  const [name, setName] = useState('');
  const [brief, setBrief] = useState('');
  const [content, setContent] = useState('');

  const reset = () => {
    setFile(null);
    setUploadStep('select');
    setParsedSkills([]);
    setSelectedIndices(new Set());
    setSingleName('');
    setSingleBrief('');
    setSingleContent('');
    setName('');
    setBrief('');
    setContent('');
    setOver(false);
  };

  const parseMutation = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error('No file selected');
      const body = new FormData();
      body.append('file', file);
      return apiFetch<ParseSkillResponse>('/api/advisor/skills/parse', {
        method: 'POST',
        body,
      });
    },
    onSuccess: (data) => {
      const list = data.skills ?? [];
      setParsedSkills(list);
      if (list.length === 1) {
        setSingleName(list[0].name || '');
        setSingleBrief(list[0].description || '');
        setSingleContent(list[0].markdown || '');
      } else {
        setSelectedIndices(new Set(list.map((_, i) => i)));
      }
      setUploadStep('preview');
    },
  });

  const confirmImportMutation = useMutation({
    mutationFn: async () => {
      if (parsedSkills.length === 1) {
        return api.post('/api/advisor/skills', {
          name: singleName.trim() || parsedSkills[0].name,
          description: singleBrief.trim() || parsedSkills[0].description,
          brief: singleBrief.trim() || parsedSkills[0].description,
          content: singleContent.trim() || parsedSkills[0].markdown,
        });
      } else {
        const selected = parsedSkills.filter((_, idx) => selectedIndices.has(idx));
        if (selected.length === 0) {
          throw new Error(t('skill.noSkillSelected'));
        }
        return api.post('/api/advisor/skills', {
          skills: selected.map((s) => ({
            name: s.name,
            description: s.description,
            markdown: s.markdown,
          })),
        });
      }
    },
    onSuccess: () => {
      onCreated();
      reset();
      onClose();
    },
  });

  const saveManual = useMutation({
    mutationFn: () =>
      api.post('/api/advisor/skills', {
        ...(name.trim() ? { name: name.trim() } : {}),
        description: brief.trim(),
        brief: brief.trim(),
        ...(content.trim() ? { content: content.trim() } : {}),
      }),
    onSuccess: () => {
      onCreated();
      reset();
      onClose();
    },
  });

  const draftAi = useMutation({
    mutationFn: () =>
      apiFetch<{ content?: string; instructions?: string; name?: string; description?: string }>(
        '/api/advisor/skills/draft-content',
        {
          method: 'POST',
          body: JSON.stringify({
            name: name.trim(),
            description: brief.trim(),
          }),
        },
      ),
    onSuccess: (data) => {
      if (data.instructions?.trim()) {
        setContent(data.instructions.trim());
      } else if (data.content?.trim()) {
        setContent(data.content.trim());
      }
      if (!name.trim() && data.name?.trim()) {
        setName(data.name.trim());
      }
    },
  });

  const suggest = useMutation({
    mutationFn: () =>
      api.post<{ name?: string }>('/api/advisor/skills/suggest', { brief: brief.trim() }),
    onSuccess: (result) => {
      const next = result.name?.trim();
      if (next && name.trim() === '') {
        setName(next);
      }
    },
  });

  const handleInsertTemplate = () => {
    const title = name.trim() || '技能名称';
    const desc = brief.trim() || '在此描述技能的适用场景与触发条件...';
    const template = `# ${title}

## 适用场景
${desc}

## 执行步骤
1. 第一步：分析需求与上下文
2. 第二步：执行核心业务逻辑
3. 第三步：验证结果并输出结构化反馈

## 约束与规范
- 严格遵循输入输出规范
- 避免生成虚假或未经验证的内容
`;
    setContent(template);
  };

  useEffect(() => {
    if (!open || mode !== 'upload') {
      return;
    }
    const onPaste = (event: ClipboardEvent) => {
      const pasted = fileFromClipboard(event.clipboardData);
      if (pasted == null) {
        return;
      }
      event.preventDefault();
      setFile(pasted);
      setUploadStep('select');
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [open, mode]);

  const toggleSelect = (idx: number) => {
    setSelectedIndices((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) {
        next.delete(idx);
      } else {
        next.add(idx);
      }
      return next;
    });
  };

  const handleToggleAll = () => {
    if (selectedIndices.size === parsedSkills.length) {
      setSelectedIndices(new Set());
    } else {
      setSelectedIndices(new Set(parsedSkills.map((_, i) => i)));
    }
  };

  const pending =
    parseMutation.isPending ||
    confirmImportMutation.isPending ||
    saveManual.isPending ||
    draftAi.isPending ||
    suggest.isPending;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && !pending && onClose()}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>{t('skill.new')}</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <div className="flex w-fit gap-1 rounded-lg bg-slate-100 p-1">
            <Button
              size="sm"
              variant={mode === 'upload' ? 'default' : 'ghost'}
              onClick={() => {
                setMode('upload');
                setUploadStep('select');
              }}
            >
              {t('skill.modeUpload')}
            </Button>
            <Button
              size="sm"
              variant={mode === 'manual' ? 'default' : 'ghost'}
              onClick={() => setMode('manual')}
            >
              {t('skill.modeManual')}
            </Button>
          </div>

          {mode === 'upload' ? (
            uploadStep === 'select' ? (
              <div className="space-y-3">
                <p className="text-[13px] leading-relaxed text-slate-500">
                  {t('skill.uploadHint')}
                </p>
                {file != null ? (
                  <SkillFileCard
                    file={file}
                    onReselect={() => inputRef.current?.click()}
                    onRemove={() => {
                      setFile(null);
                      setParsedSkills([]);
                    }}
                  />
                ) : (
                  <div
                    role="button"
                    tabIndex={0}
                    onClick={() => inputRef.current?.click()}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        inputRef.current?.click();
                      }
                    }}
                    onDragOver={(event) => {
                      event.preventDefault();
                      setOver(true);
                    }}
                    onDragLeave={() => setOver(false)}
                    onDrop={(event) => {
                      event.preventDefault();
                      setOver(false);
                      const dropped = firstFile(event.dataTransfer.files);
                      if (dropped != null) {
                        setFile(dropped);
                      }
                    }}
                    className={cn(
                      'flex min-h-36 w-full cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-4 py-6 text-center transition-colors',
                      over
                        ? 'border-primary bg-primary/5'
                        : 'border-slate-200 hover:border-slate-300 hover:bg-slate-50/50',
                    )}
                  >
                    <div className="mb-2.5 flex h-11 w-11 items-center justify-center rounded-xl bg-slate-100 text-slate-500">
                      <UploadCloud className="h-5 w-5" />
                    </div>
                    <p className="text-sm font-medium text-slate-700">{t('skill.dropHint')}</p>
                    <p className="mt-1 text-xs text-slate-400">{t('skill.fileTypeHint')}</p>
                  </div>
                )}
                <input
                  ref={inputRef}
                  type="file"
                  accept=".zip,.skill,.md,.markdown,application/zip,text/markdown"
                  className="hidden"
                  onChange={(event) => {
                    const selected = firstFile(event.target.files);
                    if (selected != null) {
                      setFile(selected);
                    }
                    if (inputRef.current) {
                      inputRef.current.value = '';
                    }
                  }}
                />
              </div>
            ) : (
              /* Preview Step */
              <div className="space-y-4">
                <div className="rounded-lg bg-slate-50 p-3 border border-slate-200/80">
                  <div className="flex items-center justify-between">
                    <div>
                      <h4 className="text-sm font-semibold text-slate-900">
                        {t('skill.previewTitle')}
                      </h4>
                      <p className="text-xs text-slate-500 mt-0.5">{t('skill.previewHint')}</p>
                    </div>
                    {parsedSkills.length > 1 ? (
                      <span className="text-xs font-medium text-indigo-600 bg-indigo-50 px-2 py-1 rounded-md border border-indigo-100">
                        {t('skill.selectedCount', {
                          total: parsedSkills.length,
                          selected: selectedIndices.size,
                        })}
                      </span>
                    ) : (
                      <Badge tone="success" className="text-xs">
                        {t('skill.fileReady')}
                      </Badge>
                    )}
                  </div>
                </div>

                {parsedSkills.length === 1 ? (
                  <div className="space-y-3">
                    <label className="block space-y-1.5 text-sm text-slate-700">
                      <span className="font-medium">{t('skill.name')}</span>
                      <Input
                        value={singleName}
                        onChange={(e) => setSingleName(e.target.value)}
                        placeholder="e.g. code_review"
                      />
                    </label>
                    <label className="block space-y-1.5 text-sm text-slate-700">
                      <span className="font-medium">{t('skill.brief')}</span>
                      <Textarea
                        value={singleBrief}
                        rows={2}
                        onChange={(e) => setSingleBrief(e.target.value)}
                        placeholder={t('skill.briefPlaceholder')}
                      />
                    </label>
                    <div className="space-y-1.5">
                      <span className="text-sm font-medium text-slate-700">
                        {t('skill.content')}
                      </span>
                      <Textarea
                        value={singleContent}
                        rows={6}
                        onChange={(e) => setSingleContent(e.target.value)}
                        className="font-mono text-xs leading-relaxed"
                      />
                    </div>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between px-1">
                      <span className="text-xs text-slate-500">
                        {t('skill.selectedCount', {
                          total: parsedSkills.length,
                          selected: selectedIndices.size,
                        })}
                      </span>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        className="h-7 text-xs text-slate-600 hover:text-slate-900"
                        onClick={handleToggleAll}
                      >
                        {selectedIndices.size === parsedSkills.length
                          ? t('skill.deselectAll')
                          : t('skill.selectAll')}
                      </Button>
                    </div>
                    <div className="max-h-72 overflow-y-auto divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
                      {parsedSkills.map((item, idx) => {
                        const isSelected = selectedIndices.has(idx);
                        return (
                          <div
                            key={idx}
                            onClick={() => toggleSelect(idx)}
                            className={cn(
                              'flex items-start gap-3 p-3 cursor-pointer transition-colors text-left',
                              isSelected ? 'bg-indigo-50/40' : 'hover:bg-slate-50/80',
                            )}
                          >
                            <div className="mt-0.5 text-primary">
                              {isSelected ? (
                                <CheckSquare className="h-4 w-4 text-indigo-600" />
                              ) : (
                                <Square className="h-4 w-4 text-slate-400" />
                              )}
                            </div>
                            <div className="min-w-0 flex-1">
                              <span className="text-sm font-semibold text-slate-800 block truncate">
                                {item.name}
                              </span>
                              <p className="text-xs text-slate-500 line-clamp-2 mt-0.5 leading-relaxed">
                                {item.description || '-'}
                              </p>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )
          ) : (
            <div className="space-y-4">
              <label className="block space-y-1.5 text-sm text-slate-700">
                <span className="font-medium">{t('skill.name')}</span>
                <Input
                  value={name}
                  placeholder="e.g. code_review"
                  onChange={(event) => setName(event.target.value)}
                />
              </label>

              <label className="block space-y-1.5 text-sm text-slate-700">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{t('skill.brief')}</span>
                  {brief.trim() !== '' && name.trim() === '' ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs"
                      disabled={pending}
                      onClick={() => suggest.mutate()}
                    >
                      {suggest.isPending ? (
                        <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                      ) : null}
                      {t('skill.complete')}
                    </Button>
                  ) : null}
                </div>
                <Textarea
                  value={brief}
                  rows={2}
                  placeholder={t('skill.briefPlaceholder')}
                  onChange={(event) => setBrief(event.target.value)}
                />
              </label>

              <div className="space-y-1.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-medium text-slate-700">{t('skill.content')}</span>
                  <div className="flex items-center gap-1.5">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs font-medium text-amber-700 hover:text-amber-800"
                      disabled={pending || !brief.trim()}
                      onClick={() => draftAi.mutate()}
                      title={!brief.trim() ? t('skill.briefRequiredForDraft') : undefined}
                    >
                      {draftAi.isPending ? (
                        <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                      ) : null}
                      {t('skill.draftAi')}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      className="h-7 text-xs text-slate-600 hover:text-slate-900"
                      disabled={pending}
                      onClick={handleInsertTemplate}
                    >
                      {t('skill.insertTemplate')}
                    </Button>
                  </div>
                </div>
                <Textarea
                  value={content}
                  rows={8}
                  placeholder={t('skill.contentPlaceholder')}
                  className="font-mono text-xs leading-relaxed"
                  onChange={(event) => setContent(event.target.value)}
                />
              </div>
            </div>
          )}

          {parseMutation.isError ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {extractErrorMessage(parseMutation.error, t('skill.parseError'))}
            </div>
          ) : null}
          {confirmImportMutation.isError ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {extractErrorMessage(confirmImportMutation.error, t('skill.error'))}
            </div>
          ) : null}
          {saveManual.isError ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {extractErrorMessage(saveManual.error, t('skill.generateError'))}
            </div>
          ) : null}
          {draftAi.isError ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {extractErrorMessage(draftAi.error, t('skill.generateError'))}
            </div>
          ) : null}
          {suggest.isError ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {extractErrorMessage(suggest.error, t('skill.completeError'))}
            </div>
          ) : null}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={pending}>
            {t('chat.cancel')}
          </Button>
          {mode === 'upload' ? (
            uploadStep === 'select' ? (
              <Button
                data-testid="parse-button"
                disabled={file == null || pending}
                onClick={() => parseMutation.mutate()}
              >
                {parseMutation.isPending ? (
                  <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                ) : null}
                {t('skill.parse')}
              </Button>
            ) : (
              <>
                <Button
                  variant="outline"
                  onClick={() => setUploadStep('select')}
                  disabled={pending}
                >
                  {t('skill.backToSelect')}
                </Button>
                <Button
                  data-testid="confirm-import-button"
                  disabled={
                    pending ||
                    (parsedSkills.length > 1 && selectedIndices.size === 0) ||
                    (parsedSkills.length === 1 && (!singleName.trim() || !singleBrief.trim()))
                  }
                  onClick={() => confirmImportMutation.mutate()}
                >
                  {confirmImportMutation.isPending ? (
                    <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                  ) : null}
                  {parsedSkills.length > 1
                    ? t('skill.confirmImport', { count: selectedIndices.size })
                    : t('skill.saveDraft')}
                </Button>
              </>
            )
          ) : (
            <Button
              disabled={brief.trim() === '' || pending}
              onClick={() => saveManual.mutate()}
            >
              {saveManual.isPending ? (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
              ) : null}
              {t('skill.saveDraft')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
