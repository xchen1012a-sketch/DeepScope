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
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import * as Popover from '@radix-ui/react-popover';
import { DayPicker, type DateRange } from 'react-day-picker';
import { enUS, zhCN } from 'react-day-picker/locale';
import { ArrowRight, CalendarDays, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { dateTimeRangeBounds, formatLocalDateTime, parseLocalDateTime, presetDateTimeRange, type DateRangePreset, type DateTimeRange } from '@/lib/dateTimeRange';
import 'react-day-picker/style.css';
import './DateTimeRangePicker.css';

const presets: DateRangePreset[] = ['today', 'last7', 'last30', 'yesterday', 'thisWeek', 'thisMonth', 'lastWeek'];

function combineDate(date: Date, previous: string, end: boolean): string {
  const time = parseLocalDateTime(previous);
  const result = new Date(date);
  result.setHours(time?.getHours() ?? (end ? 23 : 0), time?.getMinutes() ?? (end ? 59 : 0), time?.getSeconds() ?? (end ? 59 : 0), 0);
  return formatLocalDateTime(result);
}

/** Calendar selection stays local until Confirm; the owning search form submits the confirmed range. */
export function DateTimeRangePicker({ value, onChange }: { value: DateTimeRange; onChange: (value: DateTimeRange) => void }) {
  const { t, i18n } = useTranslation();
  const anchor = useRef<HTMLDivElement>(null);
  const openedFromInput = useRef(false);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const [active, setActive] = useState<'start' | 'end'>('start');
  const [month, setMonth] = useState(() => parseLocalDateTime(value.start) || new Date());
  const current = open ? draft : value;
  const bounds = dateTimeRangeBounds(draft);
  const invalid = open && bounds === null;
  const start = parseLocalDateTime(draft.start);
  const end = parseLocalDateTime(draft.end);
  const activeDate = active === 'start' ? start : end;

  function changeOpen(next: boolean) {
    if (next && !open) { setDraft(value); setMonth(parseLocalDateTime(value.start) || new Date()); }
    setOpen(next);
  }
  function chooseRange(range: DateRange | undefined) {
    setDraft(previous => ({
      start: range?.from ? combineDate(range.from, previous.start, false) : '',
      end: range?.to ? combineDate(range.to, previous.end, true) : '',
    }));
    setActive(range?.to ? 'end' : 'start');
  }
  function chooseTime(unit: 'hours' | 'minutes' | 'seconds', value: number) {
    if (!activeDate) return;
    const next = new Date(activeDate);
    if (unit === 'hours') next.setHours(value);
    else if (unit === 'minutes') next.setMinutes(value);
    else next.setSeconds(value);
    setDraft(previous => ({ ...previous, [active]: formatLocalDateTime(next) }));
  }
  function confirmRange() {
    if (bounds && (draft.start || draft.end)) { onChange(draft); setOpen(false); }
  }

  return <Popover.Root open={open} onOpenChange={changeOpen}>
    <Popover.Anchor asChild>
      <div ref={anchor} className={cn('flex h-9 w-full min-w-0 items-center gap-1 rounded-lg border bg-white px-2 shadow-sm focus-within:border-primary focus-within:ring-2 focus-within:ring-ring sm:w-[24rem]', invalid ? 'border-red-300' : 'border-border')}>
        {(['start', 'end'] as const).map((side, index) => <span key={side} className="contents">
          {index ? <ArrowRight aria-hidden className="h-3.5 w-3.5 shrink-0 text-slate-400" /> : null}
          <Input type="text" aria-label={t(`dateTimeRange.${side}`)} placeholder={t(`dateTimeRange.${side}`)}
            value={current[side].replace('T', ' ')} autoComplete="off" aria-invalid={invalid}
            className="h-8 min-w-0 flex-1 rounded-none border-0 px-1 py-1 text-xs text-slate-700 shadow-none focus-visible:ring-0"
            onFocus={() => { openedFromInput.current = true; changeOpen(true); setActive(side); }}
            onKeyDown={event => { if (event.key === 'Enter' && open) { event.preventDefault(); confirmRange(); } }}
            onChange={event => { setDraft(previous => ({ ...previous, [side]: event.target.value.replace(' ', 'T') })); }} />
        </span>)}
        {value.start || value.end ? <button type="button" aria-label={t('dateTimeRange.clear')} onClick={() => { onChange({ start: '', end: '' }); setDraft({ start: '', end: '' }); setOpen(false); }}
          className="flex h-7 w-6 shrink-0 items-center justify-center rounded text-slate-400 hover:text-slate-600"><X aria-hidden className="h-3.5 w-3.5" /></button> : null}
        <Popover.Trigger asChild><button type="button" aria-label={t('dateTimeRange.open')} onClick={() => { openedFromInput.current = false; }}
          className="flex h-7 w-6 shrink-0 items-center justify-center rounded text-slate-500 focus-visible:ring-2 focus-visible:ring-ring"><CalendarDays aria-hidden className="h-4 w-4" /></button>
        </Popover.Trigger>
      </div>
    </Popover.Anchor>
    <Popover.Portal>
      <Popover.Content aria-label={t('dateTimeRange.title')} align="start" sideOffset={8} collisionPadding={12}
        onOpenAutoFocus={event => { if (openedFromInput.current) event.preventDefault(); }}
        onInteractOutside={event => { if (anchor.current?.contains(event.target as Node)) event.preventDefault(); }}
        className="z-[70] w-[min(48rem,calc(100vw-2rem))] max-h-[min(34rem,calc(100dvh-2rem))] overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-xl">
        <div className="grid md:grid-cols-[7rem_1fr_11rem]">
          <aside aria-label={t('dateTimeRange.shortcuts')} className="flex flex-wrap gap-1 border-b border-slate-100 p-3 md:flex-col md:border-b-0 md:border-r">
            {presets.map(preset => <Button key={preset} type="button" variant="ghost" size="sm" className="h-8 justify-start px-2 text-xs font-normal text-slate-700"
              onClick={() => { const next = presetDateTimeRange(preset); setDraft(next); setMonth(parseLocalDateTime(next.start)!); setActive('end'); }}>
              {t(`dateTimeRange.presets.${preset}`)}
            </Button>)}
          </aside>
          <DayPicker mode="range" selected={{ from: start || undefined, to: end || undefined }} onSelect={chooseRange}
            month={month} onMonthChange={setMonth} showOutsideDays weekStartsOn={1} captionLayout="dropdown" startMonth={new Date(1970, 0, 1)}
            locale={i18n.language.startsWith('zh') ? zhCN : enUS}
            className="advisor-date-time-range mx-auto p-3 text-sm text-slate-700" />
          <section aria-label={t('dateTimeRange.time')} className="border-t border-slate-100 p-3 md:border-l md:border-t-0">
            <div className="mb-3 flex gap-1 rounded-lg bg-slate-50 p-1">
              {(['start', 'end'] as const).map(side => <button key={side} type="button" aria-pressed={active === side}
                className={cn('flex-1 rounded px-1 py-1 text-xs', active === side ? 'bg-violet-100 text-violet-800' : 'text-slate-600')}
                onClick={() => setActive(side)}>{t(`dateTimeRange.${side}`)}</button>)}
            </div>
            <div className="grid grid-cols-3 gap-1">
              {(['hours', 'minutes', 'seconds'] as const).map(unit => <label key={unit} className="text-center text-xs text-slate-500">
                <span className="mb-2 block">{t(`dateTimeRange.${unit}`)}</span>
                <select aria-label={t(`dateTimeRange.${unit}`)} size={7} disabled={!activeDate}
                  value={activeDate ? String(unit === 'hours' ? activeDate.getHours() : unit === 'minutes' ? activeDate.getMinutes() : activeDate.getSeconds()) : '0'}
                  onChange={event => chooseTime(unit, Number(event.target.value))}
                  className="h-56 w-full rounded border-0 bg-white text-center text-sm text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-200 disabled:opacity-40 [&_option]:rounded [&_option]:px-1 [&_option]:py-1.5 [&_option:checked]:bg-violet-100 [&_option:checked]:text-violet-800">
                  {Array.from({ length: unit === 'hours' ? 24 : 60 }, (_, number) => <option key={number} value={number}>{String(number).padStart(2, '0')}</option>)}
                </select>
              </label>)}
            </div>
          </section>
        </div>
        <div className="flex items-center justify-end gap-3 border-t border-slate-100 p-3">
          {invalid ? <p role="alert" className="mr-auto text-xs text-red-600">{t('dateTimeRange.invalid')}</p> : null}
          <Button type="button" size="sm" disabled={!bounds || (!draft.start && !draft.end)}
            onClick={confirmRange}>{t('access.confirm')}</Button>
        </div>
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>;
}
