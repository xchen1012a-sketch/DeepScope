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

import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import {
  FileArchive,
  FileText,
  Image as ImageIcon,
  Loader2,
  Plus,
  RotateCcw,
  X,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import {
  ACCEPTED_ATTACHMENT_EXTENSIONS,
  cacheAttachmentPreview,
  fetchAttachmentPreviewUrl,
  getCachedAttachmentPreview,
  uploadAttachment,
  type AttachmentKind,
  type AttachmentSummary,
  type UploadedAttachment,
} from '@/api/advisor';
import { cn } from '@/lib/utils';

const IMAGE_EXT_PATTERN = /\.(png|jpe?g|webp|gif)$/i;

function inferAttachmentKind(name: string, mimeType?: string): AttachmentKind {
  if ((mimeType && mimeType.startsWith('image/')) || IMAGE_EXT_PATTERN.test(name)) {
    return 'image';
  }
  if (/\.zip$/i.test(name)) {
    return 'archive';
  }
  return 'document';
}

function createLocalObjectUrl(file: File): string | undefined {
  if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
    try {
      return URL.createObjectURL(file);
    } catch {
      return undefined;
    }
  }
  return undefined;
}

function extractTransferFiles(data: DataTransfer | null | undefined): File[] {
  if (!data) return [];
  const files: File[] = [];
  const seen = new Set<string>();
  const push = (file: File | null | undefined) => {
    if (!file || file.size <= 0) return;
    const key = `${file.name}:${file.size}:${file.type}`;
    if (seen.has(key)) return;
    seen.add(key);
    files.push(file);
  };
  if (data.files && data.files.length > 0) {
    for (const file of Array.from(data.files)) {
      push(file);
    }
  }
  if (files.length === 0 && data.items) {
    for (const item of Array.from(data.items)) {
      if (item.kind === 'file') {
        push(item.getAsFile());
      }
    }
  }
  return files;
}


export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function AttachmentKindIcon({ kind, className }: { kind: AttachmentKind; className?: string }) {
  if (kind === 'image') return <ImageIcon className={cn('h-3.5 w-3.5 shrink-0 text-indigo-500', className)} />;
  if (kind === 'archive') return <FileArchive className={cn('h-3.5 w-3.5 shrink-0 text-amber-600', className)} />;
  return <FileText className={cn('h-3.5 w-3.5 shrink-0 text-slate-500', className)} />;
}

interface PreviewTarget {
  url: string;
  name: string;
  size?: number;
}

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 5;
const ZOOM_STEP = 0.25;

function clampZoom(value: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value * 100) / 100));
}

/** 全屏高清图片预览弹层（支持按钮缩放、滚轮缩放、双击缩放、拖拽平移以及按 ESC 关闭）。 */
function ImagePreviewLightbox({
  target,
  onClose,
}: {
  target: PreviewTarget | null;
  onClose: () => void;
}) {
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const dragOriginRef = useRef<{ startX: number; startY: number; originX: number; originY: number } | null>(null);
  const didDragRef = useRef(false);

  const zoomIn = useCallback(() => {
    setScale((prev) => clampZoom(prev + ZOOM_STEP));
  }, []);

  const zoomOut = useCallback(() => {
    setScale((prev) => {
      const next = clampZoom(prev - ZOOM_STEP);
      if (next <= 1) setOffset({ x: 0, y: 0 });
      return next;
    });
  }, []);

  const resetZoom = useCallback(() => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  }, []);

  useEffect(() => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
    setDragging(false);
    dragOriginRef.current = null;
  }, [target]);

  useEffect(() => {
    if (!target) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      } else if (event.key === '+' || event.key === '=') {
        event.preventDefault();
        zoomIn();
      } else if (event.key === '-' || event.key === '_') {
        event.preventDefault();
        zoomOut();
      } else if (event.key === '0') {
        event.preventDefault();
        resetZoom();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [target, onClose, zoomIn, zoomOut, resetZoom]);

  if (!target || typeof document === 'undefined') return null;

  const zoomPercent = `${Math.round(scale * 100)}%`;

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={target.name}
      onClick={() => {
        if (didDragRef.current) {
          didDragRef.current = false;
          return;
        }
        onClose();
      }}
      onWheel={(event) => {
        event.stopPropagation();
        const delta = event.deltaY < 0 ? 0.15 : -0.15;
        setScale((prev) => {
          const next = clampZoom(prev + delta);
          if (next <= 1) setOffset({ x: 0, y: 0 });
          return next;
        });
      }}
      onMouseMove={(event) => {
        const origin = dragOriginRef.current;
        if (!origin) return;
        const dx = event.clientX - origin.startX;
        const dy = event.clientY - origin.startY;
        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
          didDragRef.current = true;
        }
        setOffset({ x: origin.originX + dx, y: origin.originY + dy });
      }}
      onMouseUp={() => {
        dragOriginRef.current = null;
        setDragging(false);
      }}
      onMouseLeave={() => {
        dragOriginRef.current = null;
        setDragging(false);
      }}
      className="fixed inset-0 z-[100] flex flex-col items-center justify-between overflow-hidden bg-slate-950/85 p-4 backdrop-blur-xs select-none sm:p-6"
    >
      <div
        onClick={(event) => event.stopPropagation()}
        className="z-10 flex w-full max-w-5xl items-center justify-between gap-3 px-1 text-white/90"
      >
        <div className="flex min-w-0 items-center gap-2 text-[13px]">
          <span className="truncate font-medium">{target.name}</span>
          {target.size && target.size > 0 ? (
            <span className="shrink-0 text-[12px] text-white/60">{formatFileSize(target.size)}</span>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white/15 text-white transition-colors hover:bg-white/25 focus:outline-none"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="relative flex min-h-0 flex-1 w-full items-center justify-center overflow-hidden">
        <img
          src={target.url}
          alt={target.name}
          draggable={false}
          onClick={(event) => event.stopPropagation()}
          onDoubleClick={(event) => {
            event.stopPropagation();
            if (scale > 1) {
              resetZoom();
            } else {
              setScale(2);
            }
          }}
          onMouseDown={(event) => {
            if (event.button !== 0) return;
            event.stopPropagation();
            didDragRef.current = false;
            dragOriginRef.current = {
              startX: event.clientX,
              startY: event.clientY,
              originX: offset.x,
              originY: offset.y,
            };
            setDragging(true);
          }}
          style={{
            transform: `translate3d(${offset.x}px, ${offset.y}px, 0) scale(${scale})`,
            transition: dragging ? 'none' : 'transform 150ms ease-out',
          }}
          className={cn(
            'max-h-[78vh] max-w-[90vw] select-none rounded-xl object-contain shadow-2xl',
            dragging ? 'cursor-grabbing' : scale > 1 ? 'cursor-grab' : 'cursor-zoom-in',
          )}
        />
      </div>

      <div
        onClick={(event) => event.stopPropagation()}
        className="z-10 flex items-center gap-1 rounded-full border border-white/15 bg-slate-900/80 px-2.5 py-1.5 text-white shadow-lg backdrop-blur-md"
      >
        <button
          type="button"
          onClick={zoomOut}
          disabled={scale <= MIN_ZOOM}
          aria-label="Zoom out"
          className="inline-flex h-7 w-7 items-center justify-center rounded-full text-white/90 transition-colors hover:bg-white/15 disabled:opacity-40"
        >
          <ZoomOut className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={resetZoom}
          className="min-w-[52px] rounded-full px-2 py-0.5 text-center font-mono text-[12px] font-medium text-white/90 transition-colors hover:bg-white/15"
        >
          {zoomPercent}
        </button>
        <button
          type="button"
          onClick={zoomIn}
          disabled={scale >= MAX_ZOOM}
          aria-label="Zoom in"
          className="inline-flex h-7 w-7 items-center justify-center rounded-full text-white/90 transition-colors hover:bg-white/15 disabled:opacity-40"
        >
          <ZoomIn className="h-4 w-4" />
        </button>
        <div className="mx-0.5 h-3.5 w-px bg-white/20" />
        <button
          type="button"
          onClick={resetZoom}
          aria-label="Reset zoom"
          className="inline-flex h-7 w-7 items-center justify-center rounded-full text-white/90 transition-colors hover:bg-white/15"
        >
          <RotateCcw className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>,
    document.body,
  );
}

/** 解析图片附件的可展示地址：优先内存/内联预览，未命中时从服务端拉取原图流。 */
function useAttachmentImageSrc(item: AttachmentSummary): { src: string | null; loading: boolean } {
  const inlineOrCached =
    item.dataUrl ||
    (typeof getCachedAttachmentPreview === 'function' ? getCachedAttachmentPreview(item.id) : undefined) ||
    null;
  const [fetchedSrc, setFetchedSrc] = useState<string | null>(inlineOrCached);
  const [fetching, setFetching] = useState(false);

  useEffect(() => {
    if (inlineOrCached) {
      setFetchedSrc(inlineOrCached);
      setFetching(false);
      return;
    }
    if (item.kind !== 'image' || !item.id || item.id.startsWith('pending-')) {
      return;
    }
    if (typeof fetchAttachmentPreviewUrl !== 'function') {
      return;
    }
    let cancelled = false;
    setFetching(true);
    void fetchAttachmentPreviewUrl(item.id).then((url) => {
      if (!cancelled) {
        setFetchedSrc(url);
        setFetching(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [inlineOrCached, item.id, item.kind]);

  const src = inlineOrCached ?? fetchedSrc;
  const isPendingUpload = Boolean((item as UploadedAttachment).uploading);
  return { src, loading: isPendingUpload || fetching };
}

/**
 * 附件上传状态管理：支持文件选择器、拖拽放入、剪贴板粘贴图片/文件。
 */
export function useComposerAttachments(initial: UploadedAttachment[] = []) {
  const { t } = useTranslation();
  const [attachments, setAttachments] = useState<UploadedAttachment[]>(initial);
  const [uploadingCount, setUploadingCount] = useState(0);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const uploading = uploadingCount > 0 || attachments.some((item) => item.uploading);

  const addFiles = useCallback(
    async (fileList: FileList | File[]) => {
      const files = Array.from(fileList);
      if (files.length === 0) return;
      setUploadError(null);

      const pendingEntries = files.map((file, idx) => {
        const name = file.name || 'image.png';
        const kind = inferAttachmentKind(name, file.type);
        const localUrl = kind === 'image' ? createLocalObjectUrl(file) : undefined;
        const tempId = `pending-${Date.now()}-${idx}-${Math.random().toString(36).slice(2, 8)}`;
        const pendingItem: UploadedAttachment = {
          id: tempId,
          name,
          size: file.size,
          kind,
          mimeType: file.type || (kind === 'image' ? 'image/png' : 'application/octet-stream'),
          dataUrl: localUrl,
          uploading: true,
        };
        return { file, tempId, localUrl, pendingItem };
      });

      setAttachments((prev) => [...prev, ...pendingEntries.map((entry) => entry.pendingItem)]);
      setUploadingCount((count) => count + pendingEntries.length);

      for (const { file, tempId, localUrl } of pendingEntries) {
        try {
          const item = await uploadAttachment(file);
          const resolvedDataUrl = item.dataUrl ?? localUrl;
          if (typeof cacheAttachmentPreview === 'function' && resolvedDataUrl) {
            cacheAttachmentPreview(item.id, resolvedDataUrl);
          }
          setAttachments((prev) =>
            prev.map((existing) =>
              existing.id === tempId
                ? { ...item, dataUrl: resolvedDataUrl, uploading: false }
                : existing,
            ),
          );
        } catch (error) {
          setAttachments((prev) => prev.filter((existing) => existing.id !== tempId));
          const message = error instanceof Error && error.message ? error.message : t('composer.uploadFailed');
          setUploadError(message);
        } finally {
          setUploadingCount((count) => Math.max(0, count - 1));
        }
      }
    },
    [t],
  );

  const openFilePicker = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const removeAttachment = useCallback((id: string) => {
    setAttachments((prev) => prev.filter((item) => item.id !== id));
  }, []);

  const clearAttachments = useCallback(() => {
    setAttachments([]);
    setUploadError(null);
  }, []);

  const handlePaste = useCallback(
    (event: React.ClipboardEvent) => {
      const files = extractTransferFiles(event.clipboardData);
      if (files.length > 0) {
        event.preventDefault();
        void addFiles(files);
      }
    },
    [addFiles],
  );

  const handleDrop = useCallback(
    (event: React.DragEvent) => {
      const files = extractTransferFiles(event.dataTransfer);
      if (files.length > 0) {
        event.preventDefault();
        event.stopPropagation();
        void addFiles(files);
      }
    },
    [addFiles],
  );

  return {
    attachments,
    setAttachments,
    uploading,
    uploadError,
    fileInputRef,
    openFilePicker,
    addFiles,
    removeAttachment,
    clearAttachments,
    handlePaste,
    handleDrop,
  };
}

function ComposerImageThumb({
  item,
  onRemove,
  onPreview,
}: {
  item: UploadedAttachment;
  onRemove: (id: string) => void;
  onPreview: (target: PreviewTarget) => void;
}) {
  const { t } = useTranslation();
  const { src, loading } = useAttachmentImageSrc(item);

  return (
    <div className="group relative h-16 w-16 shrink-0 overflow-hidden rounded-xl border border-slate-200/90 bg-slate-100 shadow-2xs">
      {src ? (
        <button
          type="button"
          disabled={loading}
          onClick={() => onPreview({ url: src, name: item.name, size: item.size })}
          title={item.name}
          className="block h-full w-full cursor-zoom-in focus:outline-none disabled:cursor-wait"
        >
          <img
            src={src}
            alt={item.name}
            className={cn(
              'h-full w-full object-cover transition-transform duration-150 group-hover:scale-105',
              loading && 'scale-100 blur-[0.5px]',
            )}
          />
          {!loading ? (
            <span className="pointer-events-none absolute inset-0 flex items-center justify-center bg-slate-900/0 opacity-0 transition-all group-hover:bg-slate-900/20 group-hover:opacity-100">
              <ZoomIn className="h-4 w-4 text-white drop-shadow-xs" />
            </span>
          ) : null}
        </button>
      ) : (
        <div className="flex h-full w-full items-center justify-center bg-slate-100">
          <ImageIcon className="h-5 w-5 text-slate-300" />
        </div>
      )}

      {loading ? (
        <div
          aria-label={t('composer.uploading')}
          className="absolute inset-0 flex items-center justify-center bg-slate-900/45 backdrop-blur-[1px]"
        >
          <Loader2 className="h-5 w-5 animate-spin text-white drop-shadow-xs" />
        </div>
      ) : null}

      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          onRemove(item.id);
        }}
        aria-label={t('composer.removeAttachment')}
        title={t('composer.removeAttachment')}
        className="absolute right-1 top-1 z-10 flex h-5 w-5 items-center justify-center rounded-full bg-slate-900/75 text-white opacity-90 shadow-xs transition-opacity hover:bg-slate-900 group-hover:opacity-100"
      >
        <X className="h-3 w-3" />
      </button>
    </div>
  );
}

/** 输入框顶部的已选附件预览条（图片展示为可点击缩略图 + 加载遮罩，文档展示为文件卡片）。 */
export function AttachmentStrip({
  attachments,
  uploading,
  uploadError,
  onRemove,
}: {
  attachments: UploadedAttachment[];
  uploading: boolean;
  uploadError: string | null;
  onRemove: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [previewTarget, setPreviewTarget] = useState<PreviewTarget | null>(null);

  if (attachments.length === 0 && !uploading && !uploadError) return null;

  const hasInlineUploadingItem = attachments.some((item) => item.uploading);

  return (
    <div className="mb-2 space-y-1.5 px-1 pt-0.5">
      <div className="flex flex-wrap items-center gap-2">
        {attachments.map((item) =>
          item.kind === 'image' ? (
            <ComposerImageThumb
              key={item.id}
              item={item}
              onRemove={onRemove}
              onPreview={setPreviewTarget}
            />
          ) : (
            <div
              key={item.id}
              className="group flex max-w-[240px] items-center gap-2 rounded-xl border border-slate-200/90 bg-slate-50/80 px-3 py-2 text-[12px] text-slate-700 shadow-2xs"
            >
              {item.uploading ? (
                <Loader2 className="h-4 w-4 shrink-0 animate-spin text-indigo-600" />
              ) : (
                <AttachmentKindIcon kind={item.kind} className="h-4 w-4" />
              )}
              <span className="truncate font-medium" title={item.name}>
                {item.name}
              </span>
              {item.size > 0 ? (
                <span className="shrink-0 text-[11px] text-slate-400">{formatFileSize(item.size)}</span>
              ) : null}
              <button
                type="button"
                onClick={() => onRemove(item.id)}
                aria-label={t('composer.removeAttachment')}
                title={t('composer.removeAttachment')}
                className="ml-0.5 shrink-0 rounded-full p-0.5 text-slate-400 transition-colors hover:bg-slate-200/70 hover:text-slate-700"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          ),
        )}
        {uploading && !hasInlineUploadingItem ? (
          <div
            aria-label={t('composer.uploading')}
            className="flex h-16 w-16 items-center justify-center rounded-xl border border-slate-200/80 bg-slate-100/80"
          >
            <Loader2 className="h-5 w-5 animate-spin text-indigo-600" />
          </div>
        ) : null}
      </div>
      {uploadError ? <p className="text-[12px] text-red-600">{uploadError}</p> : null}
      <ImagePreviewLightbox target={previewTarget} onClose={() => setPreviewTarget(null)} />
    </div>
  );
}

/** 输入框左下角的附件入口；模型与 Think 使用共享模型菜单。 */
export function ComposerToolbar({
  fileInputRef,
  onPickFiles,
  onFilesSelected,
  disabled,
}: {
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onPickFiles: () => void;
  onFilesSelected: (files: FileList) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <input
        ref={fileInputRef as React.Ref<HTMLInputElement>}
        type="file"
        multiple
        accept={ACCEPTED_ATTACHMENT_EXTENSIONS}
        className="hidden"
        onChange={(event) => {
          if (event.target.files?.length) {
            onFilesSelected(event.target.files);
            event.target.value = '';
          }
        }}
      />
      <button
        type="button"
        disabled={disabled}
        onClick={onPickFiles}
        title={t('composer.attachHint')}
        aria-label={t('composer.attach')}
        className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
      >
        <Plus className="h-4 w-4" />
      </button>
    </div>
  );
}

function VisitorImageCard({
  item,
  onPreview,
}: {
  item: AttachmentSummary;
  onPreview: (target: PreviewTarget) => void;
}) {
  const { src, loading } = useAttachmentImageSrc(item);

  if (loading && !src) {
    return (
      <div className="flex h-36 w-48 items-center justify-center rounded-xl border border-slate-200/70 bg-white/80 shadow-2xs">
        <Loader2 className="h-5 w-5 animate-spin text-indigo-500" />
      </div>
    );
  }

  if (!src) {
    return (
      <div className="inline-flex max-w-[220px] items-center gap-1.5 rounded-lg border border-border bg-white px-2.5 py-1 text-[12px] text-slate-700 shadow-2xs">
        <AttachmentKindIcon kind="image" />
        <span className="truncate font-medium" title={item.name}>
          {item.name}
        </span>
        {item.size > 0 ? (
          <span className="shrink-0 text-[11px] text-slate-400">{formatFileSize(item.size)}</span>
        ) : null}
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={() => onPreview({ url: src, name: item.name, size: item.size })}
      title={item.name}
      className="group relative overflow-hidden rounded-xl border border-black/8 bg-white shadow-xs transition-all hover:shadow-md focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
    >
      <img
        src={src}
        alt={item.name}
        className="max-h-64 max-w-[260px] cursor-zoom-in rounded-xl object-contain sm:max-w-[320px]"
      />
      <span className="pointer-events-none absolute inset-0 flex items-center justify-center bg-slate-900/0 opacity-0 transition-all group-hover:bg-slate-900/15 group-hover:opacity-100">
        <ZoomIn className="h-5 w-5 text-white drop-shadow-xs" />
      </span>
    </button>
  );
}

/** 访客气泡内展示的附件：图片直接展示缩略大图并支持点击全屏预览，其余文件展示为附件标签。 */
export function VisitorAttachmentChips({ attachments }: { attachments?: AttachmentSummary[] }) {
  const [previewTarget, setPreviewTarget] = useState<PreviewTarget | null>(null);
  if (!attachments || attachments.length === 0) return null;

  const images = attachments.filter((item) => item.kind === 'image');
  const others = attachments.filter((item) => item.kind !== 'image');

  return (
    <div className="mb-1.5 space-y-1.5">
      {images.length > 0 ? (
        <div className="flex flex-wrap justify-end gap-2">
          {images.map((item) => (
            <VisitorImageCard key={item.id} item={item} onPreview={setPreviewTarget} />
          ))}
        </div>
      ) : null}
      {others.length > 0 ? (
        <div className="flex flex-wrap justify-end gap-1.5">
          {others.map((item) => (
            <div
              key={item.id}
              className="inline-flex max-w-[220px] items-center gap-1.5 rounded-lg border border-border bg-white px-2.5 py-1 text-[12px] text-slate-700 shadow-2xs"
            >
              <AttachmentKindIcon kind={item.kind} />
              <span className="truncate font-medium" title={item.name}>
                {item.name}
              </span>
              {item.size > 0 ? (
                <span className="shrink-0 text-[11px] text-slate-400">{formatFileSize(item.size)}</span>
              ) : null}
            </div>
          ))}
        </div>
      ) : null}
      <ImagePreviewLightbox target={previewTarget} onClose={() => setPreviewTarget(null)} />
    </div>
  );
}
