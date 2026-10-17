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

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Ellipsis, Pencil, Trash2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { ConfirmActionDialog, ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { cn } from '@/lib/utils';
import { formatRelativeTime } from '@/lib/format';
import {
  advisorKeys,
  deleteSession,
  renameSession,
  type SessionSummary,
} from '@/api/advisor';
import { StatusDot } from './components/processCards';
import { useSessionList } from './useSessionList';

/**
 * 会话名称播报式滚动条目：当文本超出容器时，鼠标悬浮自动平滑横向滚动露出完整标题。
 */
function SessionTitleMarquee({
  title,
  active,
  isHovered,
}: {
  title: string;
  active?: boolean;
  isHovered?: boolean;
}) {
  const containerRef = useRef<HTMLSpanElement>(null);
  const textRef = useRef<HTMLSpanElement>(null);
  const [overflow, setOverflow] = useState(0);

  useEffect(() => {
    if (isHovered && containerRef.current && textRef.current) {
      const diff = textRef.current.scrollWidth - containerRef.current.clientWidth;
      setOverflow(diff > 4 ? diff : 0);
    } else if (!isHovered) {
      setOverflow(0);
    }
  }, [isHovered, title]);

  const duration = Math.max(2.5, overflow / 24);

  return (
    <span
      ref={containerRef}
      className="min-w-0 flex-1 overflow-hidden relative block"
    >
      <span
        ref={textRef}
        style={
          isHovered && overflow > 0
            ? {
                animation: `marquee-broadcast ${duration}s ease-in-out 0.35s infinite alternate`,
                ['--marquee-overflow' as string]: `-${overflow}px`,
              }
            : undefined
        }
        className={cn(
          'inline-block whitespace-nowrap will-change-transform',
          (!isHovered || overflow <= 0) && 'max-w-full truncate',
          active && 'font-medium',
        )}
      >
        {title}
      </span>
    </span>
  );
}

/** 单行会话条目：支持双击行内修改名称、悬浮播报式滚动、去除了素描气泡图标。 */
function SessionNavItem({
  session,
  active,
  hint,
  status,
  title,
  actionTriggerRef,
  onOpen,
  onRename,
  onStartRenameDialog,
  onStartDeleteDialog,
}: {
  session: SessionSummary;
  active: boolean;
  hint: string;
  status: string;
  title: string;
  actionTriggerRef: React.MutableRefObject<HTMLButtonElement | null>;
  onOpen: (id: string) => void;
  onRename: (session: SessionSummary, newTitle: string) => void;
  onStartRenameDialog: (session: SessionSummary) => void;
  onStartDeleteDialog: (session: SessionSummary) => void;
}) {
  const { t } = useTranslation();
  const [isEditing, setIsEditing] = useState(false);
  const [editTitle, setEditTitle] = useState(session.title?.trim() || '');
  const [isHovered, setIsHovered] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const startInlineEdit = () => {
    setIsEditing(true);
    setEditTitle(session.title?.trim() || title);
  };

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const commitInlineEdit = () => {
    const trimmed = editTitle.trim();
    setIsEditing(false);
    if (!trimmed || trimmed === session.title) return;
    onRename(session, trimmed);
  };

  const cancelInlineEdit = () => {
    setIsEditing(false);
  };

  return (
    <div
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      className={cn(
        'group flex h-10 [@media(pointer:coarse)]:h-11 items-center gap-2 rounded-lg pl-3 pr-1 text-sm transition-colors',
        active ? 'bg-accent text-accent-foreground' : 'text-slate-600 hover:bg-slate-100/80',
      )}
    >
      {isEditing ? (
        <div className="flex h-full min-w-0 flex-1 items-center pr-1">
          <input
            ref={inputRef}
            type="text"
            data-testid="session-inline-rename-input"
            value={editTitle}
            onChange={(e) => setEditTitle(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                commitInlineEdit();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                cancelInlineEdit();
              }
            }}
            onBlur={commitInlineEdit}
            onClick={(e) => e.stopPropagation()}
            onDoubleClick={(e) => e.stopPropagation()}
            className="h-7 w-full rounded border border-indigo-400 bg-white px-2 text-sm text-slate-800 shadow-2xs outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"
            autoFocus
          />
        </div>
      ) : (
        <button
          type="button"
          title={hint}
          aria-current={active ? 'page' : undefined}
          className="flex h-full min-w-0 flex-1 items-center gap-2 rounded-md text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring overflow-hidden cursor-pointer"
          onClick={() => onOpen(session.sessionId)}
          onDoubleClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            startInlineEdit();
          }}
        >
          {session.status !== 'idle' ? (
            <span className="flex h-[18px] w-[18px] shrink-0 items-center justify-center" aria-hidden="true">
              <StatusDot status={session.status} />
            </span>
          ) : null}
          <SessionTitleMarquee title={title} active={active} isHovered={isHovered} />
          {session.status !== 'idle' ? <span className="sr-only">{status}</span> : null}
        </button>
      )}

      <DropdownMenu.Root modal={false}>
        <DropdownMenu.Trigger asChild>
          <button
            type="button"
            aria-label={t('chat.moreActions', { title })}
            onPointerDown={(event) => {
              actionTriggerRef.current = event.currentTarget;
            }}
            onFocus={(event) => {
              actionTriggerRef.current = event.currentTarget;
            }}
            className={cn(
              'flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-slate-400 hover:bg-white/80 hover:text-slate-700 focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring group-hover:opacity-100 data-[state=open]:opacity-100 [@media(pointer:coarse)]:opacity-100 [@media(pointer:coarse)]:h-11 [@media(pointer:coarse)]:w-11',
              active ? 'opacity-100' : 'opacity-0',
            )}
          >
            <Ellipsis className="h-4 w-4" />
          </button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            align="start"
            sideOffset={6}
            collisionPadding={12}
            className="z-50 w-44 rounded-xl border border-slate-200/80 bg-white p-1.5 text-sm text-slate-700 shadow-xl ring-1 ring-slate-900/5 outline-none focus:outline-none focus-visible:outline-none"
          >
            <DropdownMenu.Item
              onSelect={() => onStartRenameDialog(session)}
              className="flex h-9 cursor-pointer items-center gap-2.5 rounded-lg px-2.5 text-xs font-medium text-slate-700 outline-none transition-colors hover:bg-slate-100 data-[highlighted]:bg-slate-100 data-[highlighted]:text-slate-900"
            >
              <Pencil className="h-3.5 w-3.5 text-slate-400" />
              {t('chat.rename')}
            </DropdownMenu.Item>
            <DropdownMenu.Item
              onSelect={() => onStartDeleteDialog(session)}
              className="flex h-9 cursor-pointer items-center gap-2.5 rounded-lg px-2.5 text-xs font-medium text-red-600 outline-none transition-colors hover:bg-red-50 data-[highlighted]:bg-red-50 data-[highlighted]:text-red-700"
            >
              <Trash2 className="h-3.5 w-3.5 text-red-500" />
              {t('chat.delete')}
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
    </div>
  );
}

/** 挂在最左栏「会话」下面的列表：双击行内修改名称、悬浮播报式滚动、重命名弹窗、删除。新建走顶部「新建会话」。 */
export function SessionNav({ onNavigate }: { onNavigate?: () => void }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const queryClient = useQueryClient();
  const actionTrigger = useRef<HTMLButtonElement | null>(null);
  const [renameTarget, setRenameTarget] = useState<SessionSummary | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<SessionSummary | null>(null);

  const sessionsQuery = useSessionList();

  const renameMutation = useMutation({
    mutationFn: ({ session, title }: { session: SessionSummary; title: string }) =>
      renameSession(session.sessionId, title),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: advisorKeys.sessions }),
  });
  const deleteMutation = useMutation({
    mutationFn: (session: SessionSummary) => deleteSession(session.sessionId),
    onSuccess: (_result, session) => {
      queryClient.invalidateQueries({ queryKey: advisorKeys.sessions });
      if (session.sessionId === sessionId) navigate('/chat', { replace: true });
    },
  });

  const sessions = useMemo(
    () => [...(sessionsQuery.data ?? [])].sort((a, b) => b.updatedAt - a.updatedAt),
    [sessionsQuery.data],
  );

  const openSession = (id: string) => {
    onNavigate?.();
    navigate(`/chat/${id}`);
  };

  const handleInlineRename = (session: SessionSummary, newTitle: string) => {
    renameMutation.mutate({ session, title: newTitle });
  };

  const submitRename = () => {
    const title = renameTitle.trim();
    if (!renameTarget || !title) return;
    const session = renameTarget;
    setRenameTarget(null);
    renameMutation.mutate({ session, title });
  };

  const submitDelete = () => {
    if (!deleteTarget) return;
    const session = deleteTarget;
    setDeleteTarget(null);
    deleteMutation.mutate(session);
  };

  return (
    <div>
      <nav aria-label={t('nav.recentSessions')}>
        {sessions.length === 0 && !sessionsQuery.isLoading ? (
          <p className="px-3 py-4 text-sm leading-relaxed text-slate-500">{t('chat.emptySessions')}</p>
        ) : null}
        {sessions.map((session) => {
          const title = session.title?.trim() ? session.title : t('chat.untitled');
          const active = session.sessionId === sessionId;
          const status = t(`chat.sessionStatus.${session.status}`);
          const hint = [title, formatRelativeTime(session.updatedAt, i18n.language), session.agentName, status].filter(Boolean).join(' · ');
          return (
            <SessionNavItem
              key={session.sessionId}
              session={session}
              active={active}
              hint={hint}
              status={status}
              title={title}
              actionTriggerRef={actionTrigger}
              onOpen={openSession}
              onRename={handleInlineRename}
              onStartRenameDialog={(s) => {
                setRenameTarget(s);
                setRenameTitle(s.title ?? '');
              }}
              onStartDeleteDialog={setDeleteTarget}
            />
          );
        })}
      </nav>

      <ConfirmActionDialog
        open={renameTarget !== null}
        title={t('chat.renameTitle')}
        confirmText={t('chat.save')}
        cancelText={t('chat.cancel')}
        confirmDisabled={!renameTitle.trim()}
        isConfirming={renameMutation.isPending}
        onConfirm={submitRename}
        onClose={() => setRenameTarget(null)}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          actionTrigger.current?.focus();
        }}
      >
        <div className="pt-1">
          <label className="block text-xs font-medium text-slate-600 mb-1.5" htmlFor="session-rename-input">
            {t('chat.sessionTitleLabel')}
          </label>
          <Input
            id="session-rename-input"
            value={renameTitle}
            maxLength={120}
            autoFocus
            onChange={(event) => setRenameTitle(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && renameTitle.trim()) {
                event.preventDefault();
                submitRename();
              }
            }}
            placeholder={t('chat.sessionTitlePlaceholder', '输入会话名称…')}
            className="h-10 text-sm rounded-xl border-slate-200 focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"
          />
        </div>
      </ConfirmActionDialog>

      <ConfirmDeleteDialog
        open={deleteTarget !== null}
        title={t('chat.deleteTitle')}
        targetName={deleteTarget?.title?.trim() ? deleteTarget.title : t('chat.untitled')}
        description={t('chat.deleteBody')}
        onConfirm={submitDelete}
        onClose={() => setDeleteTarget(null)}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          actionTrigger.current?.focus();
        }}
      />
    </div>
  );
}
