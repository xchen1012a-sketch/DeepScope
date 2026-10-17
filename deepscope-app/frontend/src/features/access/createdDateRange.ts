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
import type { ListFilters } from '@/api/listQuery';
import { dateTimeRangeBounds, type DateTimeRange } from '@/lib/dateTimeRange';
export type CreatedDateRange = DateTimeRange;

/** The API uses an exclusive end; the shared picker lets users include the selected final second. */
export function createdDateFilters(range: CreatedDateRange): ListFilters | null {
  const bounds = dateTimeRangeBounds(range);
  return bounds ? { createdFrom: bounds.from === null ? '' : String(bounds.from), createdBefore: bounds.before === null ? '' : String(bounds.before) } : null;
}
