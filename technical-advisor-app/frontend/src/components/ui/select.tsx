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

import React, { useEffect, useRef, useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Check, ChevronDown, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';

export interface SelectOption {
  value: string;
  label: string;
}

interface SelectBaseProps {
  options: SelectOption[];
  labelOptions?: SelectOption[];
  search?: {
    onChange: (query: string) => void;
    placeholder: string;
    loading?: boolean;
    error?: boolean;
    onRetry?: () => void;
  };
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  allowClear?: boolean;
  onClear?: () => void;
  'aria-label'?: string;
  size?: 'sm' | 'md';
}

export type AppSelectProps = SelectBaseProps & (
  | { multiple?: false; value: string; onChange: (value: string) => void }
  | { multiple: true; value: string[]; onChange: (value: string[]) => void }
);

export function AppSelect(props: AppSelectProps) {
  const {
    options,
    placeholder = '请选择',
    disabled = false,
    className,
    allowClear = false,
    onClear,
    'aria-label': ariaLabel,
    size = 'md',
  } = props;
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [pickedOptions, setPickedOptions] = useState<SelectOption[]>([]);
  const searchInput = useRef<HTMLInputElement>(null);
  const onSearch = props.search?.onChange;
  const searchable = Boolean(props.search);
  useEffect(() => {
    if (!open || !searchable) return;
    const frame = requestAnimationFrame(() => searchInput.current?.focus());
    return () => cancelAnimationFrame(frame);
  }, [open, searchable]);
  useEffect(() => {
    if (!onSearch) return;
    const timer = setTimeout(() => onSearch(searchText.trim()), 200);
    return () => clearTimeout(timer);
  }, [searchText, onSearch]);
  const selectedValues = props.multiple ? props.value : props.value ? [props.value] : [];
  const selectedOptions = selectedValues.map(value => options.find(option => option.value === value)
    ?? props.labelOptions?.find(option => option.value === value)
    ?? pickedOptions.find(option => option.value === value)).filter((option): option is SelectOption => Boolean(option));
  const selectedLabel = selectedOptions.map(option => option.label).join(', ');
  const OptionItem = props.multiple ? DropdownMenu.CheckboxItem : DropdownMenu.Item;

  const clear = () => {
    if (onClear) onClear();
    else if (props.multiple) props.onChange([]);
    else props.onChange('');
  };

  const select = (value: string) => {
    const next = props.multiple
      ? props.value.includes(value) ? props.value.filter(item => item !== value) : [...props.value, value]
      : [value];
    // Remember labels for the user's selection when a later server search replaces the options.
    setPickedOptions(current => next.flatMap(id => {
      const option = options.find(item => item.value === id) ?? props.labelOptions?.find(item => item.value === id)
        ?? current.find(item => item.value === id);
      return option ? [option] : [];
    }));
    if (props.multiple) {
      props.onChange(next);
    } else props.onChange(value);
  };

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    clear();
  };

  return (
    <DropdownMenu.Root modal={false} open={open} onOpenChange={next => { setOpen(next); if (!next) setSearchText(''); }}>
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          disabled={disabled}
          aria-label={ariaLabel || selectedLabel || placeholder}
          className={cn(
            'inline-flex items-center justify-between gap-2 rounded-lg border border-slate-200 bg-white text-slate-700 shadow-sm transition-colors hover:border-slate-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 text-left',
            size === 'sm' ? 'h-8 px-2.5 text-xs' : 'h-10 px-3 text-sm',
            className,
          )}
        >
          <span className="truncate" title={selectedLabel || undefined}>
            {selectedLabel ? (
              selectedLabel
            ) : (
              <span className="text-slate-400">{placeholder}</span>
            )}
          </span>
          <div className="flex items-center gap-1 shrink-0">
            {allowClear && selectedValues.length > 0 && !disabled ? (
              <span
                role="button"
                tabIndex={0}
                onClick={handleClear}
                onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); clear(); } }}
                className="rounded p-0.5 text-slate-400 hover:text-slate-600 hover:bg-slate-100 cursor-pointer"
                aria-label={t('access.reset')}
              >
                <X className="h-3.5 w-3.5" />
              </span>
            ) : null}
            <ChevronDown
              className={cn(
                'shrink-0 text-slate-400 transition-transform duration-200',
                size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4',
                open && 'rotate-180',
              )}
            />
          </div>
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          sideOffset={4}
          align="start"
          collisionPadding={12}
          className="z-50 w-[var(--radix-dropdown-menu-trigger-width)] max-h-64 overflow-y-auto rounded-xl border border-slate-200 bg-white p-1 text-slate-700 shadow-lg animate-in fade-in-0 zoom-in-95"
        >
          {props.search ? <div className="sticky top-0 z-10 bg-white pb-1">
            <Input ref={searchInput} type="search" value={searchText} placeholder={props.search.placeholder}
              aria-label={props.search.placeholder} className="h-8 px-2.5 text-xs" onChange={event => setSearchText(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Escape') return;
                event.stopPropagation();
                if (event.key === 'ArrowDown') {
                  event.preventDefault();
                  event.currentTarget.closest('[role="menu"]')?.querySelector<HTMLElement>('[role="menuitemcheckbox"], [role="menuitem"]')?.focus();
                }
              }} />
          </div> : null}
          {props.search?.loading ? <p role="status" className="px-2.5 py-2 text-xs text-slate-500">{t('modelSettings.manage.loadingOptions')}</p>
            : props.search?.error ? <div role="alert" className="px-2.5 py-2 text-xs text-red-700">
              {t('table.loadError')}
              {props.search.onRetry ? <button type="button" className="ml-2 underline" onClick={props.search.onRetry}>{t('table.retry')}</button> : null}
            </div> : props.search && !options.length ? <p role="status" className="px-2.5 py-2 text-xs text-slate-500">{t('table.noResults')}</p> : null}
          {!props.search?.loading && !props.search?.error && options.map((opt) => {
            const isSelected = props.multiple ? selectedValues.includes(opt.value) : opt.value === props.value;
            return (
              <OptionItem
                key={opt.value}
                {...(props.multiple ? { checked: isSelected } : {})}
                onSelect={event => { if (props.multiple) event.preventDefault(); select(opt.value); }}
                className={cn(
                  'flex cursor-pointer items-center justify-between rounded-lg px-2.5 py-1.5 text-xs font-medium outline-none transition-colors data-[highlighted]:bg-slate-100',
                  isSelected && 'bg-slate-50 text-indigo-600',
                )}
              >
                <span className="truncate">{opt.label}</span>
                {isSelected ? <Check className="ml-2 h-3.5 w-3.5 shrink-0 text-indigo-600" /> : null}
              </OptionItem>
            );
          })}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

export { AppSelect as Select };
