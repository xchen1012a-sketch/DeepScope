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

import commonEn from './locales/en/common.json';
import appEn from './locales/en/app.json';
import commonZh from './locales/zh-CN/common.json';
import appZh from './locales/zh-CN/app.json';

/** Aggregated i18n resources shared by the app instance and the test instance. */
export const i18nResources = {
  en: { common: commonEn, app: appEn },
  'zh-CN': { common: commonZh, app: appZh },
} as const;

export const SUPPORTED_LANGUAGES = ['zh-CN', 'en'] as const;
export type AppLanguage = (typeof SUPPORTED_LANGUAGES)[number];
export const DEFAULT_LANGUAGE: AppLanguage = 'zh-CN';
export const LOCALE_STORAGE_KEY = 'controlplane.console.locale';
