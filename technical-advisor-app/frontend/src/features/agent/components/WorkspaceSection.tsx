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

import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, FolderTree, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  agentKeys,
  createAgentFile,
  deleteAgentFile,
  getAgentTree,
  readAgentFile,
  saveAgentFile,
} from '@/api/agent';
import { buildFileTree, type FileTreeNode } from '../fileTree';
import { cn } from '@/lib/utils';
import { useAgentEditGuard, useAgentEditor } from '../agentEditGuard';

/** 工作区页签：左侧文件树 + 右侧编辑器（保存/删除/新建）。 */
export function WorkspaceSection({
  agentId,
  canEdit,
  readOnlyReason,
}: {
  agentId: string;
  canEdit: boolean;
  readOnlyReason: string;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const editGuard = useAgentEditGuard();
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState('');
  const [newFileOpen, setNewFileOpen] = useState(false);
  const [newFilePath, setNewFilePath] = useState('');
  const [deleteOpen, setDeleteOpen] = useState(false);

  const treeQuery = useQuery({
    queryKey: agentKeys.tree(agentId),
    queryFn: async () => (await getAgentTree(agentId)).map((file) => file.path),
  });
  const tree = useMemo(() => buildFileTree(treeQuery.data ?? []), [treeQuery.data]);

  const fileQuery = useQuery({
    queryKey: agentKeys.file(agentId, selected ?? ''),
    queryFn: () => readAgentFile(agentId, selected ?? ''),
    enabled: selected !== null,
  });

  useEffect(() => {
    if (fileQuery.data) setContent(fileQuery.data.content);
  }, [fileQuery.data]);

  const invalidateTree = () => {
    queryClient.invalidateQueries({ queryKey: agentKeys.tree(agentId) });
    queryClient.invalidateQueries({ queryKey: agentKeys.agent(agentId) });
    queryClient.invalidateQueries({ queryKey: agentKeys.agents });
  };

  const saveMutation = useMutation({
    mutationFn: (value: { path: string; content: string }) => saveAgentFile(agentId, value.path, value.content),
    onSuccess: (_result, value) => { queryClient.setQueryData(agentKeys.file(agentId, value.path), { content: value.content }); },
  });

  const createMutation = useMutation({
    mutationFn: (path: string) => createAgentFile(agentId, path, ''),
    onSuccess: (_result, path) => {
      invalidateTree();
      setSelected(path);
      setNewFileOpen(false);
      setNewFilePath('');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (path: string) => deleteAgentFile(agentId, path),
    onSuccess: () => {
      invalidateTree();
      setSelected(null);
      setDeleteOpen(false);
    },
  });

  const dirty = canEdit && selected !== null && fileQuery.data !== undefined && content !== fileQuery.data.content;
  useAgentEditor('workspace', { dirty, busy: saveMutation.isPending || createMutation.isPending || deleteMutation.isPending, save: () => saveMutation.mutateAsync({ path: selected ?? '', content }), discard: () => setContent(fileQuery.data?.content ?? '') });

  return (
    <section>
      <div className="mb-4">
        <h2 className="text-[18px] font-semibold tracking-tight text-slate-900">{t('agent.sections.workspace')}</h2>
        <p className="mt-1.5 text-[13px] leading-relaxed text-slate-500">{t('agent.workspace.hint')}</p>
        {!canEdit ? <p className="mt-1.5 text-[12.5px] text-amber-700">{readOnlyReason}</p> : null}
      </div>

      {treeQuery.isPending ? (
        <div className="flex min-h-[420px] overflow-hidden rounded-2xl border border-border bg-white">
          <div className="w-64 shrink-0 space-y-1.5 border-r border-border bg-muted/30 p-2">
            {Array.from({ length: 9 }, (_, index) => (
              <Skeleton key={index} className="h-6 w-full" />
            ))}
          </div>
          <div className="flex-1 p-4">
            <Skeleton className="h-[360px] w-full rounded-xl" />
          </div>
        </div>
      ) : tree.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-slate-300 bg-muted/40 px-4 py-6 text-center text-[13px] text-slate-500">
          {t('agent.workspace.empty')}
        </p>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-border bg-white">
          <div className="flex min-h-[420px]">
            <div className="w-64 shrink-0 border-r border-border bg-muted/30 py-2">
              {canEdit ? (
                <div className="px-2 pb-2">
                  <Button variant="outline" size="sm" className="w-full" onClick={() => setNewFileOpen(true)}>
                    <Plus className="h-4 w-4" />
                    {t('agent.workspace.newFile')}
                  </Button>
                </div>
              ) : null}
              <div className="max-h-[520px] overflow-auto">
                {tree.map((node) => (
                  <TreeRow
                    key={node.path}
                    node={node}
                    depth={0}
                    selected={selected}
                    onSelect={(path) => editGuard ? editGuard.request(() => setSelected(path)) : setSelected(path)}
                  />
                ))}
              </div>
            </div>

            <div className="min-w-0 flex-1 p-4">
              {selected === null ? (
                <p className="flex h-full items-center justify-center text-[13px] text-slate-400">
                  {t('agent.workspace.selectHint')}
                </p>
              ) : fileQuery.isPending ? (
                <Skeleton className="h-[380px] w-full rounded-xl" />
              ) : (
                <div className="flex h-full flex-col">
                  <div className="mb-3 flex flex-wrap items-center gap-2">
                    <code className="min-w-0 flex-1 break-all rounded-md bg-muted px-2 py-1 font-mono text-[12px] text-slate-600">
                      {selected}
                    </code>
                    {canEdit ? (
                      <>
                        <Button size="sm" disabled={!dirty || saveMutation.isPending} onClick={() => saveMutation.mutate({ path: selected ?? '', content })}>
                          {t('agent.workspace.save')}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          aria-label={t('agent.workspace.deleteFile')}
                          onClick={() => setDeleteOpen(true)}
                        >
                          <Trash2 className="h-4 w-4 text-slate-400" />
                        </Button>
                      </>
                    ) : null}
                  </div>
                  <Textarea
                    value={content}
                    readOnly={!canEdit || saveMutation.isPending}
                    onChange={(event) => setContent(event.target.value)}
                    className="min-h-[380px] flex-1 font-mono text-[12.5px] leading-relaxed"
                  />
                  {saveMutation.isSuccess && !dirty ? (
                    <span className="mt-2 text-[12px] text-emerald-600">{t('agent.infoSaved')}</span>
                  ) : null}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 新建文件 */}
      <Dialog open={newFileOpen} onOpenChange={(open) => !open && setNewFileOpen(false)}>
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>{t('agent.workspace.newFileTitle')}</DialogTitle>
            <DialogDescription>{t('agent.workspace.newFileHint')}</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <Input
              value={newFilePath}
              placeholder="knowledge/notes.md"
              onChange={(event) => setNewFilePath(event.target.value)}
            />
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setNewFileOpen(false)}>
              {t('chat.cancel')}
            </Button>
            <Button
              disabled={!newFilePath.trim() || createMutation.isPending}
              onClick={() => createMutation.mutate(newFilePath.trim())}
            >
              {t('agent.workspace.newFile')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除文件确认 */}
      <ConfirmDeleteDialog
        open={deleteOpen}
        title={t('agent.workspace.deleteFileTitle')}
        description={
          <div className="space-y-1">
            <span className="font-mono text-xs text-slate-500 block truncate">{selected}</span>
            <p className="text-sm text-slate-600">{t('agent.workspace.deleteFileBody')}</p>
          </div>
        }
        confirmText={t('agent.workspace.deleteFile')}
        isDeleting={deleteMutation.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => selected && deleteMutation.mutate(selected)}
      />
    </section>
  );
}

function TreeRow({
  node,
  depth,
  selected,
  onSelect,
}: {
  node: FileTreeNode;
  depth: number;
  selected: string | null;
  onSelect: (path: string) => void;
}) {
  if (node.kind === 'dir') {
    return (
      <div>
        <div
          className="flex items-center gap-1.5 px-2 py-1 text-[12px] font-medium text-slate-500"
          style={{ paddingLeft: `${8 + depth * 14}px` }}
        >
          <FolderTree className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          <span className="truncate">{node.name}</span>
        </div>
        {node.children.map((child) => (
          <TreeRow key={child.path} node={child} depth={depth + 1} selected={selected} onSelect={onSelect} />
        ))}
      </div>
    );
  }
  return (
    <button
      type="button"
      onClick={() => onSelect(node.path)}
      className={cn(
        'flex w-full items-center gap-1.5 px-2 py-1 text-left text-[12px] transition-colors',
        selected === node.path
          ? 'bg-accent font-medium text-accent-foreground'
          : 'text-slate-600 hover:bg-white/70 hover:text-slate-800',
      )}
      style={{ paddingLeft: `${8 + depth * 14}px` }}
    >
      <FileText className="h-3.5 w-3.5 shrink-0 text-slate-400" />
      <span className="truncate font-mono">{node.name}</span>
    </button>
  );
}
