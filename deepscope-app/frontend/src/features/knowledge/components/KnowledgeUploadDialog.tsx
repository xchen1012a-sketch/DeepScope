// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, FileText, Loader2, Upload, X } from 'lucide-react';
import { Dialog, DialogBody, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import type { UploadItem } from './useKnowledgeUpload';

interface Props {
  open: boolean; items: UploadItem[]; uploading: boolean; error: string;
  onClose: () => void; onPick: () => void; onFiles: (files: File[]) => void;
  onRemove: (id: string) => void; onUpload: () => void;
  fileInput?: ReactNode;
}
export function KnowledgeUploadDialog({ open, items, uploading, error, onClose, onPick, onFiles, onRemove, onUpload, fileInput }: Props) {
  const { t } = useTranslation();
  const [dragging, setDragging] = useState(false);
  const pending = items.filter(item => item.status === 'pending' || item.status === 'failed').length;
  return <Dialog open={open} onOpenChange={next => { if (!next && !uploading) onClose(); }}>
    <DialogContent size="lg" onEscapeKeyDown={event => { if (uploading) event.preventDefault(); }} onPointerDownOutside={event => { if (uploading) event.preventDefault(); }}>
      {fileInput}
      <DialogHeader><DialogTitle>{t('knowledge.editor.documents.upload')}</DialogTitle></DialogHeader>
      <DialogBody>
        <div tabIndex={0} role="region" aria-label={t('knowledge.uploadDialog.area')} aria-busy={uploading}
          className={`h-72 max-h-[50vh] overflow-y-auto overscroll-contain rounded-xl border-2 border-dashed p-4 outline-none focus-visible:ring-2 focus-visible:ring-primary ${uploading ? 'cursor-wait' : 'cursor-pointer'} ${dragging ? 'border-primary bg-indigo-50' : 'border-slate-300 bg-slate-50/60'}`}
          onClick={event => {
            if (uploading || (event.target as HTMLElement).closest('button, [data-upload-files]')) return;
            onPick();
          }}
          onKeyDown={event => {
            if (event.target !== event.currentTarget || uploading || !['Enter', ' '].includes(event.key)) return;
            event.preventDefault(); onPick();
          }}
          onDragOver={event => { event.preventDefault(); if (!uploading) setDragging(true); }}
          onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false); }}
          onDrop={event => { event.preventDefault(); event.stopPropagation(); setDragging(false); if (!uploading) onFiles(Array.from(event.dataTransfer.files)); }}
          onPaste={event => {
            event.stopPropagation();
            if (uploading) return;
            const files = Array.from(event.clipboardData.files);
            if (files.length) { event.preventDefault(); onFiles(files); return; }
            const text = event.clipboardData.getData('text/plain');
            if (text.trim()) { event.preventDefault(); onFiles([new File([text], `notes-${Date.now()}.txt`, { type: 'text/plain' })]); }
          }}>
          {!items.length ? <div className="flex flex-col items-center gap-2 py-6 text-center">
            <Upload aria-hidden className="h-6 w-6 text-slate-400" />
            <Button variant="ghost" disabled={uploading} onClick={onPick}>{t('knowledge.uploadDialog.choose')}</Button>
            <p className="text-xs text-slate-500">{t('knowledge.uploadDialog.paste')}</p>
            <p className="text-[11px] text-slate-400">PDF · DOCX · Markdown · TXT · HTML</p>
            <p className="text-[11px] text-slate-500">{t('knowledge.uploadDialog.limits')}</p>
          </div> : <>
          <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2 sm:grid-cols-3">{items.map(item => <div data-upload-files key={item.id} className="flex min-w-0 cursor-default items-start gap-2 rounded-lg border border-indigo-100 bg-indigo-50/70 p-2.5">
            {item.status === 'uploading' ? <Loader2 aria-hidden className="h-4 w-4 shrink-0 animate-spin text-primary" /> : item.status === 'uploaded' ? <Check aria-hidden className="h-4 w-4 shrink-0 text-emerald-600" /> : <FileText aria-hidden className="h-4 w-4 shrink-0 text-slate-400" />}
            <div className="min-w-0 flex-1"><p className="break-all text-xs font-medium text-slate-800">{item.file.name}</p><p className="mt-1 text-[11px] text-slate-500">{Math.max(1, Math.ceil(item.file.size / 1024))} KB</p>
              <span role="status" className={`mt-1 block text-[11px] ${item.status === 'failed' ? 'text-red-600' : 'text-slate-500'}`}>{t(`knowledge.uploadDialog.${item.status}`)}</span>
            </div>
            {item.status !== 'uploaded' ? <Button variant="ghost" size="icon" disabled={uploading} aria-label={t('knowledge.uploadDialog.remove', { name: item.file.name })} onClick={() => onRemove(item.id)}><X className="h-3.5 w-3.5" /></Button> : null}
          </div>)}</div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
            <Button variant="ghost" size="sm" disabled={uploading} onClick={onPick}>{t('knowledge.uploadAddMore')}</Button>
            <p className="text-[11px] text-slate-500">{t('knowledge.uploadDialog.limits')}</p>
          </div>
          </>}
        </div>
        {error ? <p role="alert" className="mt-3 text-xs text-red-600">{error}</p> : null}
      </DialogBody>
      <DialogFooter><Button variant="outline" disabled={uploading} onClick={onClose}>{t('knowledge.uploadDialog.close')}</Button><Button disabled={uploading || !pending} onClick={onUpload}>{t(uploading ? 'knowledge.uploading' : 'knowledge.uploadDialog.submit', { count: pending })}</Button></DialogFooter>
    </DialogContent>
  </Dialog>;
}
