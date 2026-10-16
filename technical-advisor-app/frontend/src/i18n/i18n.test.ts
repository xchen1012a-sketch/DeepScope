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
import enApp from './locales/en/app.json';
import enCommon from './locales/en/common.json';
import zhApp from './locales/zh-CN/app.json';
import zhCommon from './locales/zh-CN/common.json';

type JsonRecord = Record<string, unknown>;

function flatKeys(value: JsonRecord, prefix = ''): string[] {
  return Object.entries(value).flatMap(([key, entry]) =>
    entry !== null && typeof entry === 'object'
      ? flatKeys(entry as JsonRecord, `${prefix}${key}.`)
      : [`${prefix}${key}`],
  );
}

function flatEntries(value: JsonRecord, prefix = ''): Array<[string, unknown]> {
  return Object.entries(value).flatMap(([key, entry]) =>
    entry !== null && typeof entry === 'object'
      ? flatEntries(entry as JsonRecord, `${prefix}${key}.`)
      : [[`${prefix}${key}`, entry]],
  );
}

const namespaces: Array<[string, JsonRecord, JsonRecord]> = [
  ['app', enApp as JsonRecord, zhApp as JsonRecord],
  ['common', enCommon as JsonRecord, zhCommon as JsonRecord],
];

describe('i18n dictionaries', () => {
  it.each(namespaces)('%s namespace keeps en and zh-CN keys aligned', (_ns, en, zh) => {
    expect(flatKeys(zh).sort()).toEqual(flatKeys(en).sort());
  });

  it.each(namespaces)('%s namespace preserves interpolation parameters across languages', (_ns, en, zh) => {
    const english = new Map(flatEntries(en));
    for (const [key, value] of flatEntries(zh)) {
      const parameters = (text: unknown) => [...String(text).matchAll(/\{\{(\w+)\}\}/g)].map(match => match[1]).sort();
      expect(parameters(value), `interpolation mismatch at ${key}`).toEqual(parameters(english.get(key)));
    }
  });

  it.each(namespaces)('%s namespace has no empty translation values', (_ns, _en, zh) => {
    for (const [key, value] of flatEntries(zh)) {
      expect(String(value ?? '').trim(), `empty value at ${key}`).not.toBe('');
    }
  });
});
