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
import { useTranslation } from 'react-i18next';
import { DateTimeRangePicker } from '@/components/DateTimeRangePicker';
import type { CreatedDateRange } from './createdDateRange';

export function CreatedDateFilter({ value, onChange }: { value: CreatedDateRange; onChange: (value: CreatedDateRange) => void }) {
  const { t } = useTranslation();
  return <div role="group" aria-label={t('access.createdAt')} className="flex flex-wrap items-center gap-2 text-xs text-slate-600 sm:!w-auto">
    <span className="shrink-0">{t('access.createdAt')}</span>
    <DateTimeRangePicker value={value} onChange={onChange} />
  </div>;
}
