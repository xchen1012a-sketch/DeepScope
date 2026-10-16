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
import { ApiError } from '@/lib/apiClient';
import { toolFailure } from './toolFailure';

const dictionary: Record<string, string> = {
  'tool.importError': 'No callable operation was found.',
  'tool.errors.address_required': 'No call address was found.',
  'tool.errors.connection_failed': 'The address was recognised but nothing answered.',
  'tool.errors.auth_failed': 'The service rejected the credentials.',
};

const t = (key: string) => dictionary[key] ?? key;

function apiError(body: unknown, status = 422) {
  return new ApiError(status, typeof body === 'string' ? body : JSON.stringify(body));
}

describe('toolFailure', () => {
  it('reads a missing address as itself rather than as a generic failure', () => {
    expect(
      toolFailure(
        apiError({ code: 'address_required', messageKey: 'tool.errors.address_required' }),
        t,
      ),
    ).toBe('No call address was found.');
  });

  it('keeps an unreachable service apart from rejected credentials', () => {
    const unreachable = apiError({
      code: 'connection_failed',
      messageKey: 'tool.errors.connection_failed',
    });
    const rejected = apiError({
      code: 'auth_failed',
      messageKey: 'tool.errors.auth_failed',
    });

    expect(toolFailure(unreachable, t)).not.toBe(toolFailure(rejected, t));
  });

  it('falls back to the generic line when the dictionary has no such key', () => {
    expect(toolFailure(apiError({ messageKey: 'tool.errors.something_new' }), t)).toBe(
      'No callable operation was found.',
    );
  });

  it('falls back when the body is not JSON or the failure is not an API error', () => {
    expect(toolFailure(apiError('<html>502</html>', 502), t)).toBe(
      'No callable operation was found.',
    );
    expect(toolFailure(new Error('offline'), t)).toBe('No callable operation was found.');
  });
});
