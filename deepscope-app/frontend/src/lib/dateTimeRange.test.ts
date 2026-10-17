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
import { describe, expect, it } from 'vitest';
import { dateTimeRangeBounds, parseLocalDateTime, presetDateTimeRange } from './dateTimeRange';

describe('local date and time ranges', () => {
  it('includes the selected final second and rejects invalid calendar dates', () => {
    const result = dateTimeRangeBounds({ start: '2026-10-15T09:30:00', end: '2026-10-16T18:20:45' })!;
    const start = new Date(result.from!);
    const before = new Date(result.before!);
    expect([start.getDate(), start.getHours(), start.getMinutes()]).toEqual([15, 9, 30]);
    expect([before.getDate(), before.getHours(), before.getMinutes(), before.getSeconds()]).toEqual([16, 18, 20, 46]);
    expect(parseLocalDateTime('2026-02-30T00:00:00')).toBeNull();
    expect(parseLocalDateTime('2026-10-16T24:01:00')).toBeNull();
    expect(dateTimeRangeBounds({ start: '2026-10-16T10:00:00', end: '2026-10-16T09:00:00' })).toBeNull();
  });

  it('builds Monday-based week and full month shortcuts across month boundaries', () => {
    const now = new Date(2026, 2, 1, 12, 0, 0);
    expect(presetDateTimeRange('thisWeek', now)).toEqual({ start: '2026-02-23T00:00:00', end: '2026-03-01T23:59:59' });
    expect(presetDateTimeRange('lastWeek', now)).toEqual({ start: '2026-02-16T00:00:00', end: '2026-02-22T23:59:59' });
    expect(presetDateTimeRange('thisMonth', now)).toEqual({ start: '2026-03-01T00:00:00', end: '2026-03-31T23:59:59' });
  });

  it('supports open boundaries and date-only values without losing the end day', () => {
    const result = dateTimeRangeBounds({ start: '', end: '2026-10-16' })!;
    expect(result.from).toBeNull();
    const before = new Date(result.before!);
    expect([before.getDate(), before.getHours()]).toEqual([17, 0]);
    expect(dateTimeRangeBounds({ start: '2026-10-16T00:00:00', end: '' })?.before).toBeNull();
    const mixed = dateTimeRangeBounds({ start: '2026-10-16T12:00:00', end: '2026-10-16' });
    expect(mixed).not.toBeNull();
    expect(new Date(mixed!.before!).getDate()).toBe(17);
  });
});
