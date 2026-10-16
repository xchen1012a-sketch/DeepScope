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

import { ArrowLeft } from 'lucide-react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { AppSelect } from '@/components/ui/select';
import { cn } from '@/lib/utils';

export const accountSections = [
  { id: 'profile', label: 'account.sections.profile' },
  { id: 'security', label: 'account.sections.security' },
  { id: 'preferences', label: 'account.sections.preferences' },
  { id: 'sessions', label: 'account.sections.sessions' },
] as const;

export function SettingsSidebar({ mobile = false }: { mobile?: boolean }) {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const selected = accountSections.find(section => location.pathname === '/account/' + section.id)?.id ?? 'profile';
  if (mobile) return <div className="flex min-w-0 items-center gap-2 lg:hidden">
    <AppSelect aria-label={t('account.chooseSection')} value={selected} options={accountSections.map(section => ({ value: section.id, label: t(section.label) }))}
      onChange={id => navigate('/account/' + id, { replace: true, state: location.state })} size="sm" />
  </div>;
  return <aside className="hidden min-h-0 w-44 shrink-0 flex-col overflow-y-auto border-r border-slate-100 bg-slate-50/70 px-3 py-6 lg:flex xl:w-48">
    <nav aria-label={t('account.navigation')} className="space-y-1">{accountSections.map(section =>
      <Button key={section.id} variant="ghost" asChild className={cn('h-10 w-full justify-start px-3 [@media(pointer:coarse)]:h-11', selected === section.id ? 'bg-accent font-medium text-accent-foreground' : 'text-muted-foreground')}>
        <NavLink replace state={location.state} to={'/account/' + section.id}>{t(section.label)}</NavLink>
      </Button>)}</nav>
  </aside>;
}

export function AccountBackButton() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const close = () => {
    const state = location.state as { accountEntry?: boolean } | null;
    if (state?.accountEntry) navigate(-1);
    else navigate('/chat', { replace: true });
  };
  return <Button variant="ghost" size="icon" aria-label={t('account.back')} onClick={close}>
    <ArrowLeft aria-hidden className="h-4 w-4" />
  </Button>;
}
