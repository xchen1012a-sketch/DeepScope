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
export interface DateTimeRange { start: string; end: string }
export type DateRangePreset = 'today' | 'last7' | 'last30' | 'yesterday' | 'thisWeek' | 'thisMonth' | 'lastWeek';

export function formatLocalDateTime(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function parseLocalDateTime(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(value);
  if (!match || Number(match[1]) < 1970) return null;
  const normalized = `${match[1]}-${match[2]}-${match[3]}T${match[4] || '00'}:${match[5] || '00'}:${match[6] || '00'}`;
  const date = new Date(normalized);
  return Number.isFinite(date.getTime()) && formatLocalDateTime(date) === normalized ? date : null;
}

/** Date-only values include the entire end day; time values include the selected final second. */
export function dateTimeRangeBounds(value: DateTimeRange): { from: number | null; before: number | null } | null {
  const start = parseLocalDateTime(value.start);
  const end = parseLocalDateTime(value.end);
  if ((value.start && !start) || (value.end && !end)) return null;
  let before: number | null = null;
  if (end) {
    if (value.end.length === 10) { end.setDate(end.getDate() + 1); before = end.getTime(); }
    else before = end.getTime() + 1000;
  }
  if (start && before !== null && start.getTime() >= before) return null;
  return { from: start?.getTime() ?? null, before };
}

export function presetDateTimeRange(preset: DateRangePreset, now = new Date()): DateTimeRange {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const end = new Date(start);
  const mondayOffset = (start.getDay() + 6) % 7;
  if (preset === 'last7' || preset === 'last30') start.setDate(start.getDate() - (preset === 'last7' ? 6 : 29));
  if (preset === 'yesterday') { start.setDate(start.getDate() - 1); end.setDate(end.getDate() - 1); }
  if (preset === 'thisWeek' || preset === 'lastWeek') {
    start.setDate(start.getDate() - mondayOffset - (preset === 'lastWeek' ? 7 : 0));
    end.setTime(start.getTime()); end.setDate(end.getDate() + 6);
  }
  if (preset === 'thisMonth') { start.setDate(1); end.setMonth(end.getMonth() + 1, 0); }
  end.setHours(23, 59, 59, 0);
  return { start: formatLocalDateTime(start), end: formatLocalDateTime(end) };
}
