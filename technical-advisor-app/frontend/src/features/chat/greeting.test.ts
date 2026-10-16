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
import { shanghaiGreeting } from './greeting';

describe('Shanghai greeting', () => {
  it.each([
    ['2026-10-15T17:00:00Z', 'lateNight'],
    ['2026-10-16T00:00:00Z', 'earlyMorning'],
    ['2026-10-16T02:00:00Z', 'morning'],
    ['2026-10-16T04:00:00Z', 'noon'],
    ['2026-10-16T06:00:00Z', 'afternoon'],
    ['2026-10-16T12:00:00Z', 'evening'],
  ])('uses Shanghai time for %s', (time, expected) => {
    expect(shanghaiGreeting(new Date(time))).toBe(expected);
  });
});
