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

import { Navigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Page } from '@/components/Page';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { accountSections, SettingsSidebar } from './SettingsSidebar';
import ProfileSection from './ProfileSection';
import SecuritySection from './SecuritySection';
import PreferencesSection from './PreferencesSection';
import SessionsSection from './SessionsSection';

const sections = { profile: ProfileSection, security: SecuritySection, preferences: PreferencesSection, sessions: SessionsSection };
export default function AccountPage() {
  const location = useLocation();
  const { t } = useTranslation();
  const section = accountSections.find(item => location.pathname === '/account/' + item.id);
  if (!section) return <Navigate to="/account/profile" replace state={location.state} />;
  const Content = sections[section.id];
  return (
    <Page fill className="flex flex-col gap-4 space-y-0 bg-slate-50 py-4 sm:py-6">
      <div className="shrink-0 lg:hidden"><SettingsSidebar mobile /></div>
      <div className="mx-auto flex min-h-0 w-full max-w-6xl flex-1 items-stretch overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-xs">
        <SettingsSidebar />
        <Card className="flex h-full min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-none border-0 bg-transparent shadow-none">
          <CardHeader className="shrink-0 px-5 pb-4 pt-6 sm:px-8">
            <CardTitle id="account-section-title" className="text-lg font-medium text-slate-900">{t(section.label)}</CardTitle>
            <p className="text-xs leading-5 text-slate-500">{t(`account.sectionHelp.${section.id}`)}</p>
          </CardHeader>
          <CardContent key={section.id} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-6 sm:px-8" role="region" aria-labelledby="account-section-title" tabIndex={0}>
            <Content key={section.id} />
          </CardContent>
        </Card>
      </div>
    </Page>
  );
}
