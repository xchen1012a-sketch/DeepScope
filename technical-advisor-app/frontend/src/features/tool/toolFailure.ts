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

import { ApiError } from '@/lib/apiClient';

/**
 * The tool API answers failures with a stable message key, so a missing address, an unreachable
 * service and rejected credentials each read as themselves instead of one generic line. A key the
 * dictionary does not know falls back to the generic line rather than showing the raw key.
 */
export function toolFailure(error: unknown, t: (key: string) => string): string {
  if (error instanceof ApiError && error.body) {
    try {
      const parsed = JSON.parse(error.body) as { messageKey?: string; code?: string };
      if (parsed.messageKey) {
        const localized = t(parsed.messageKey);
        if (localized !== parsed.messageKey) return localized;
      }
      if (parsed.code) {
        const errorKey = `tool.errors.${parsed.code}`;
        const localized = t(errorKey);
        if (localized !== errorKey) return localized;
      }
    } catch {
      // A body that is not JSON tells us nothing, so the generic line stands.
    }
  }
  return t('tool.importError');
}
