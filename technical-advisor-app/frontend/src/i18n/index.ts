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

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import {
  DEFAULT_LANGUAGE,
  LOCALE_STORAGE_KEY,
  SUPPORTED_LANGUAGES,
  i18nResources,
  type AppLanguage,
} from './resources';

function storedLocale(): string {
  try {
    return window.localStorage.getItem(LOCALE_STORAGE_KEY) || '';
  } catch {
    return '';
  }
}

function initialLanguage(): AppLanguage {
  const stored = storedLocale();
  return (SUPPORTED_LANGUAGES as readonly string[]).includes(stored) ? (stored as AppLanguage) : DEFAULT_LANGUAGE;
}

void i18n.use(initReactI18next).init({
  lng: initialLanguage(),
  fallbackLng: 'en',
  defaultNS: 'app',
  resources: i18nResources,
  interpolation: { escapeValue: false },
});

i18n.on('languageChanged', (lng) => {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = lng;
    document.title = i18n.t('pageTitle');
  }
  try {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, lng);
  } catch {
    /* ignore persistence failures (e.g. private browsing) */
  }
});

if (typeof document !== 'undefined') {
  document.documentElement.lang = i18n.language;
  document.title = i18n.t('pageTitle');
}

export default i18n;
