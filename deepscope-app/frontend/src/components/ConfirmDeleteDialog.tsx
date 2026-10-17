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

import React from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';

export interface ConfirmDeleteDialogProps {
  open: boolean;
  title: string;
  targetName?: string;
  description?: React.ReactNode;
  children?: React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: 'destructive' | 'default';
  isDeleting?: boolean;
  isConfirming?: boolean;
  confirmDisabled?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
}

export function ConfirmDeleteDialog({
  open,
  title,
  targetName,
  description,
  children,
  confirmText,
  cancelText,
  confirmVariant = 'destructive',
  isDeleting = false,
  isConfirming,
  confirmDisabled = false,
  onConfirm,
  onClose,
  onCloseAutoFocus,
}: ConfirmDeleteDialogProps) {
  const { t } = useTranslation();
  const loading = isConfirming !== undefined ? isConfirming : isDeleting;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && !loading && onClose()}>
      <DialogContent
        size="sm"
        className="max-w-[460px] rounded-2xl p-6 sm:p-7"
        onCloseAutoFocus={onCloseAutoFocus}
      >
        <div className="space-y-3">
          <DialogTitle className="text-lg font-semibold tracking-tight text-slate-900 pr-8">
            {title}
          </DialogTitle>
          {description !== undefined ? (
            <div className="text-sm leading-relaxed text-slate-600">{description}</div>
          ) : targetName ? (
            <p className="text-sm leading-relaxed text-slate-600">
              {`这将永久删除“${targetName}”。此操作无法撤销。`}
            </p>
          ) : null}
          {children}
        </div>

        <div className="mt-6 flex items-center justify-end gap-2.5">
          <Button
            type="button"
            variant="outline"
            className="rounded-full px-5 h-9 text-xs font-medium text-slate-700 border-slate-200 hover:bg-slate-100"
            disabled={loading}
            onClick={onClose}
          >
            {cancelText ?? t('chat.cancel', '取消')}
          </Button>
          <Button
            type="button"
            variant={confirmVariant === 'destructive' ? 'destructive' : 'default'}
            className={
              confirmVariant === 'destructive'
                ? 'rounded-full px-5 h-9 text-xs font-medium bg-red-600 hover:bg-red-700 text-white shadow-sm'
                : 'rounded-full px-5 h-9 text-xs font-medium bg-primary hover:bg-primary/90 text-white shadow-sm'
            }
            disabled={loading || confirmDisabled}
            onClick={onConfirm}
          >
            {loading ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : null}
            {confirmText ?? (confirmVariant === 'destructive' ? t('skill.delete', '删除') : t('chat.save', '确定'))}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function ConfirmActionDialog(props: ConfirmDeleteDialogProps) {
  return <ConfirmDeleteDialog confirmVariant="default" {...props} />;
}
