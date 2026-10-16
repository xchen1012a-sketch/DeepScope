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

import { Languages } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';

/** Top-bar toggle between Simplified Chinese (default) and English. */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const isChinese = i18n.language.startsWith('zh');
  const target = isChinese ? 'en' : 'zh-CN';
  return (
    <Button
      variant="ghost"
      size="sm"
      className="h-8 shrink-0 gap-1.5 px-2 text-xs font-medium text-muted-foreground"
      aria-label={t('language.switch')}
      title={t('language.switch')}
      onClick={() => void i18n.changeLanguage(target)}
    >
      <Languages className="h-4 w-4" />
      {isChinese ? 'EN' : '中文'}
    </Button>
  );
}
