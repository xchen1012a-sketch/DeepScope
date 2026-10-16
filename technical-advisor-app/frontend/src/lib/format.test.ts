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
import { formatBytes, formatRelativeTime, formatTokenCount } from './format';

const NOW = Date.UTC(2026, 9, 10, 12, 0, 0);

describe('formatRelativeTime', () => {
  it('formats timestamps within the last minute as now', () => {
    // 不同 ICU 版本对 0 分钟的措辞不同（now / this minute），但不带数字。
    expect(formatRelativeTime(NOW, 'en', NOW)).toMatch(/^(now|this minute)$/);
    expect(formatRelativeTime(NOW - 10_000, 'en', NOW)).toMatch(/^(now|this minute)$/);
  });

  it('formats minutes, hours and days relative to the given now', () => {
    expect(formatRelativeTime(NOW - 5 * 60_000, 'en', NOW)).toBe('5 minutes ago');
    expect(formatRelativeTime(NOW - 3 * 3_600_000, 'en', NOW)).toBe('3 hours ago');
    expect(formatRelativeTime(NOW - 2 * 86_400_000, 'en', NOW)).toBe('2 days ago');
  });
});
describe('formatTokenCount', () => {
  it.each([
    [0, '0'], [512, '512'], [1_000, '1K'], [24_500, '24.5K'],
    [128_000, '128K'], [1_000_000, '1M'], [1_048_576, '1M'],
  ])('formats %i tokens as %s', (tokens, expected) => {
    expect(formatTokenCount(tokens)).toBe(expected);
  });
});

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [500, '500 B'],
    [1024, '1.0 KB'],
    [24_576, '24.0 KB'],
    [1_048_576, '1.0 MB'],
    [104_857_600, '100.0 MB'],
  ])('formats %i bytes as %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected);
  });
});
