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

import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { apiFetch } from '@/lib/apiClient';
import { KnowledgeUploadDialog } from './KnowledgeUploadDialog';

export interface UploadItem { id: string; file: File; status: 'pending' | 'uploading' | 'uploaded' | 'failed' }

/** One upload action is shared by documents and the empty retrieval state. */
export function useKnowledgeUpload(libraryId: string, canEdit: boolean, onChanged: () => void) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);
  const [pickerVersion, setPickerVersion] = useState(0);
  const busy = useRef(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<UploadItem[]>([]);
  const stageFiles = useCallback((files: FileList | File[]) => {
    if (!canEdit || busy.current || !files.length) return;
    setItems(current => [...(open ? current : current.filter(item => item.status !== 'uploaded')), ...Array.from(files).map(file => ({ id: crypto.randomUUID(), file, status: 'pending' as const }))]);
    setError(''); setOpen(true);
  }, [canEdit, open]);
  const mark = (id: string, status: UploadItem['status']) => setItems(current => current.map(item => item.id === id ? { ...item, status } : item));
  const submit = async () => {
    if (busy.current || !canEdit) return;
    const pending = items.filter(item => item.status === 'pending' || item.status === 'failed');
    if (!pending.length) return;
    busy.current = true; setUploading(true); setError('');
    let uploaded = 0;
    try {
      for (const item of pending) {
        mark(item.id, 'uploading');
        try {
          const body = new FormData(); body.append('file', item.file);
          await apiFetch(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}/documents`, { method: 'POST', body });
          mark(item.id, 'uploaded'); uploaded++;
        } catch { mark(item.id, 'failed'); setError(t('knowledge.editor.documents.uploadFailed')); }
      }
    } finally {
      busy.current = false; setUploading(false);
      if (uploaded === pending.length) {
        setOpen(false);
        setItems([]);
      }
      if (uploaded) onChanged();
    }
  };
  const uploadFiles = useCallback(async (files: FileList | File[]) => {
    if (!canEdit || !files.length || busy.current) return;
    busy.current = true;
    setUploading(true);
    setError('');
    let uploaded = 0;
    try {
      for (const file of Array.from(files)) {
        const body = new FormData();
        body.append('file', file);
        await apiFetch(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}/documents`, { method: 'POST', body });
        uploaded++;
      }
    } catch {
      setError(t('knowledge.editor.documents.uploadFailed'));
    } finally {
      if (uploaded > 0) onChanged();
      busy.current = false;
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  }, [canEdit, libraryId, onChanged, t]);
  return {
    uploading, error, uploadFiles, stageFiles,
    selectFiles: () => {
      if (!canEdit || busy.current) return;
      if (!open) setItems(current => current.filter(item => item.status !== 'uploaded'));
      if (inputRef.current) inputRef.current.value = '';
      setError(''); setOpen(true);
    },
    input: <KnowledgeUploadDialog fileInput={<input key={pickerVersion} ref={inputRef} type="file" multiple className="hidden" aria-label={t('knowledge.editor.documents.selectFiles')} accept=".md,.markdown,.txt,.html,.htm,.pdf,.docx" onChange={event => {
      const files = Array.from(event.currentTarget.files ?? []);
      event.currentTarget.value = '';
      if (files.length) stageFiles(files);
    }} />}
      open={open} items={items} uploading={uploading} error={error} onClose={() => { if (!busy.current) setOpen(false); }} onPick={() => {
        if (busy.current || !canEdit || !inputRef.current) return;
        inputRef.current.value = '';
        inputRef.current.click();
      }} onFiles={stageFiles} onRemove={id => {
        if (busy.current) return;
        setItems(current => current.filter(item => item.id !== id));
        if (inputRef.current) inputRef.current.value = '';
        setPickerVersion(current => current + 1);
      }} onUpload={() => void submit()} />,
  };
}

export type KnowledgeUpload = ReturnType<typeof useKnowledgeUpload>;
