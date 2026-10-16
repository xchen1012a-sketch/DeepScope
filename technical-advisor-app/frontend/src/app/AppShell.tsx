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

import { Suspense, useState, type ComponentType } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import * as ScrollArea from '@radix-ui/react-scroll-area';
import {
  Blocks, BookOpen, Bot, ChevronRight, ChevronUp, LogOut, Menu,
  KeyRound, MessageSquare, PanelLeftClose, PanelLeftOpen, Plus, ShieldCheck, UserRound,
  SlidersHorizontal, Sparkles,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';
import { Button } from '@/components/ui/button';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { LanguageSwitcher } from './LanguageSwitcher';
import { getUsername, can, logout } from '@/api/auth';
import { SessionNav } from '@/features/chat/SessionNav';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { AccountBackButton } from '@/features/account/SettingsSidebar';
import { AccountAvatar } from '@/features/account/AccountAvatar';

interface NavItem {
  to: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  anyOf: string[];
}

interface NavGroup {
  id: 'workspace' | 'settings' | 'access';
  label: string;
  items: NavItem[];
}

const NAV: NavGroup[] = [
  { id: 'workspace', label: 'nav.workspace', items: [
    { to: '/agent', label: 'nav.agent', icon: Bot, anyOf: ['agent.view', 'agent.manage'] },
    { to: '/tools', label: 'nav.tools', icon: Blocks, anyOf: ['tool.view', 'tool.manage'] },
    { to: '/skills', label: 'nav.skills', icon: Sparkles, anyOf: ['skill.view', 'skill.manage'] },
    { to: '/knowledge', label: 'nav.knowledge', icon: BookOpen, anyOf: ['knowledge.view', 'knowledge.manage'] },
  ] },
  { id: 'settings', label: 'nav.preferences', items: [
    { to: '/settings', label: 'nav.settings', icon: SlidersHorizontal, anyOf: ['model.view', 'settings.manage'] },
  ] },
  { id: 'access', label: 'nav.group.access', items: [
    { to: '/access/users', label: 'nav.users', icon: UserRound, anyOf: ['user.view', 'rbac.manage'] },
    { to: '/access/roles', label: 'nav.roles', icon: KeyRound, anyOf: ['role.view', 'rbac.manage'] },
  ] },
];

const rowClass = 'flex h-10 w-full [@media(pointer:coarse)]:h-11 [@media(max-height:600px)]:!h-8 items-center gap-2.5 rounded-lg text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';
const iconClass = 'h-[18px] w-[18px] shrink-0 stroke-[1.7]';

function PageSkeleton() {
  return (
    <div className="mx-auto max-w-[1440px] space-y-4 px-5 py-7 sm:px-8">
      <Skeleton className="ml-auto h-10 w-28 rounded-lg" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 3 }, (_, index) => (
          <Skeleton key={index} className="h-36 rounded-2xl" />
        ))}
      </div>
    </div>
  );
}

function isActive(pathname: string, to: string, end = false): boolean {
  if (end || to === '/') return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

export default function AppShell() {
  const { t } = useTranslation();
  const location = useLocation();
  const { pathname } = location;
  const navigate = useNavigate();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [recentSessionsOpen, setRecentSessionsOpen] = useState(true);
  const [accessOpen, setAccessOpen] = useState(() => pathname.startsWith('/access'));
  const [openGroup, setOpenGroup] = useState<'workspace' | 'settings' | null>(() =>
    pathname.startsWith('/settings') || pathname.startsWith('/access') ? 'settings'
      : NAV[0].items.some(item => isActive(pathname, item.to)) ? 'workspace' : null);
  const canUseChat = can('chat.use');
  const groups = NAV.map(group => ({
    ...group,
    items: group.items.filter(item => item.anyOf.some(permission => can(permission))),
  })).filter(group => group.items.length > 0);
  const workspace = groups.find(group => group.id === 'workspace');
  const settings = groups.find(group => group.id === 'settings');
  const access = groups.find(group => group.id === 'access');
  const activeGroup = groups.find(group => group.items.some(item => isActive(pathname, item.to)));
  const activeItem = activeGroup?.items.find(item => isActive(pathname, item.to));

  const onChat = pathname === '/chat' || pathname.startsWith('/chat/');
  const inAccount = pathname === '/account' || pathname.startsWith('/account/');
  const onKnowledgeList = pathname === '/knowledge' || pathname === '/knowledge/';
  const identity = useAccountIdentity();
  const username = identity?.nickname?.trim() || identity?.username || getUsername();
  const permissionsExpanded = accessOpen;
  const groupContentClass = 'mx-2 mb-2 [@media(max-height:600px)]:max-h-[max(80px,calc(100dvh-320px))] [@media(max-height:600px)]:overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden';
  const groupPanelClass = 'rounded-xl border border-slate-200/80 bg-white shadow-sm';

  const groupHeader = (group: 'workspace' | 'settings', label: string, Icon: NavItem['icon']) => (
    <button type="button" aria-label={t(label)} aria-expanded={openGroup === group}
      aria-controls={`sidebar-group-${group}`} className={cn(rowClass, 'px-3 text-slate-600 hover:bg-slate-100/80')}
      onClick={() => setOpenGroup(current => current === group ? null : group)}>
      <Icon className={iconClass} /><span className="min-w-0 flex-1 truncate text-left">{t(label)}</span>
      <ChevronRight className={cn('h-3.5 w-3.5 text-slate-400 transition-transform', openGroup === group && 'rotate-90')} />
    </button>
  );

  const handleSignOut = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const renderLink = (item: NavItem) => (
    <NavLink
      key={item.to}
      to={item.to}
      aria-label={t(item.label)}
      title={collapsed ? t(item.label) : undefined}
      onClick={() => { setMobileNavOpen(false); setOpenGroup(item.to.startsWith('/settings') || item.to.startsWith('/access') ? 'settings' : 'workspace'); if (item.to.startsWith('/access')) setAccessOpen(true); }}
      className={({ isActive: active }) => cn(
        rowClass,
        collapsed ? 'justify-center px-0' : 'px-3',
        active ? 'bg-accent font-medium text-accent-foreground' : 'text-slate-600 hover:bg-slate-100/80 hover:text-slate-900',
      )}
    >
      <item.icon className={iconClass} />
      {!collapsed ? <span className="min-w-0 flex-1 truncate">{t(item.label)}</span> : null}
    </NavLink>
  );

  return (
    <div className="flex h-full min-h-0 bg-canvas overflow-hidden">
      <a
        href="#main-content"
        className="absolute left-3 top-3 z-50 -translate-y-20 rounded-md bg-primary px-3 py-2 text-sm text-white transition-transform focus:translate-y-0"
      >
        {t('skipToContent')}
      </a>

      {mobileNavOpen ? (
        <button
          type="button"
          aria-label={t('closeNavigation')}
          className="fixed inset-0 z-30 bg-slate-950/35 lg:hidden"
          onClick={() => setMobileNavOpen(false)}
        />
      ) : null}

      <aside
        aria-label={t('nav.sidebar')}
        className={cn(
          'fixed inset-y-0 left-0 z-40 flex h-full min-h-0 shrink-0 flex-col overflow-hidden bg-[#fafafb] text-sm transition-[width,transform] duration-200 motion-reduce:transition-none lg:static lg:translate-x-0',
          collapsed ? 'w-16' : 'w-[248px]',
          mobileNavOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <div className={cn('flex shrink-0 items-center gap-2.5 pb-5 pt-5 [@media(max-height:600px)]:py-2', collapsed ? 'flex-col px-2' : 'px-[18px]')}>
          <BrandLogo className="h-[26px] w-[26px] rounded-none bg-transparent ring-0" iconClassName="h-[18px] w-[18px]" />
          {!collapsed ? <span className="min-w-0 flex-1 truncate text-[15px] font-semibold tracking-tight text-slate-900">{t('brand.name')}</span> : null}
          <button
            type="button"
            aria-label={t(collapsed ? 'nav.expand' : 'nav.collapse')}
            onClick={() => setCollapsed(value => !value)}
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-slate-400 hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {collapsed ? <PanelLeftOpen className={iconClass} /> : <PanelLeftClose className={iconClass} />}
          </button>
        </div>

        <nav aria-label={t('nav.sidebar')} className={cn('flex min-h-0 flex-col overflow-hidden pb-2 [@media(max-height:600px)]:pb-0', canUseChat ? 'flex-1' : 'shrink-0', collapsed ? 'px-2' : 'px-3')}>
          {canUseChat ? (
            <>
              <NavLink
                to="/chat" end aria-label={t('nav.home')} title={collapsed ? t('nav.home') : undefined}
                onClick={() => setMobileNavOpen(false)}
                className={cn(rowClass, 'shrink-0 border border-border/80 bg-white font-medium text-slate-700 shadow-sm hover:bg-accent', collapsed ? 'justify-center px-0' : 'px-3')}
              >
                <Plus className={cn(iconClass, 'text-primary')} />
                {!collapsed ? <span>{t('nav.home')}</span> : null}
              </NavLink>
              {collapsed ? (
                <button type="button" aria-label={t('nav.sessions')} title={t('nav.sessions')} onClick={() => setCollapsed(false)} className={cn(rowClass, 'mt-2 justify-center text-slate-500 hover:bg-slate-100 [@media(max-height:600px)]:mt-0')}>
                  <MessageSquare className={iconClass} />
                </button>
              ) : null}
                <section hidden={collapsed} className={cn('mt-5 min-h-0 [@media(max-height:600px)]:mt-2', recentSessionsOpen ? 'flex-1' : 'shrink-0', collapsed ? 'hidden' : 'flex flex-col')} aria-labelledby="recent-sessions-heading">
                  <h2 id="recent-sessions-heading" className="mb-1.5 shrink-0">
                    <button type="button" aria-expanded={recentSessionsOpen} aria-controls="recent-sessions-list"
                      onClick={() => setRecentSessionsOpen(open => !open)}
                      className="group/recent flex w-full items-center gap-2 rounded-md px-3 py-1 text-left text-sm font-medium text-slate-500 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                      <span>{t('nav.recentSessions')}</span>
                      <ChevronRight aria-hidden="true" className={cn('h-3.5 w-3.5 shrink-0 opacity-0 group-hover/recent:opacity-100 group-focus-visible/recent:opacity-100', recentSessionsOpen && 'rotate-90')} />
                    </button>
                  </h2>
                  <ScrollArea.Root id="recent-sessions-list" hidden={!recentSessionsOpen} type="scroll" scrollHideDelay={600} className="relative min-h-0 flex-1 overflow-hidden">
                    <ScrollArea.Viewport role="region" aria-label={t('nav.recentSessions')} tabIndex={0} className="h-full w-full overscroll-y-contain">
                      <SessionNav onNavigate={() => setMobileNavOpen(false)} />
                    </ScrollArea.Viewport>
                    <ScrollArea.Scrollbar orientation="vertical" className="flex w-1.5 touch-none select-none p-px">
                      <ScrollArea.Thumb className="relative flex-1 rounded-full bg-slate-300/80" />
                    </ScrollArea.Scrollbar>
                  </ScrollArea.Root>
                </section>
            </>
          ) : null}
        </nav>

        {workspace ? <section aria-label={t('nav.workspace')} className={cn('shrink-0', collapsed ? 'px-2' : 'mx-3 mt-3', !collapsed && openGroup === 'workspace' && groupPanelClass)}>
          {collapsed ? workspace.items.map(renderLink) : <>
            {groupHeader('workspace', 'nav.workspace', Blocks)}
            {openGroup === 'workspace' ? <div id="sidebar-group-workspace" className={groupContentClass}>
              {workspace.items.map(renderLink)}
            </div> : null}
          </>}
        </section> : null}

        {settings || access ? (
          <section aria-label={t('nav.preferences')} className={cn('shrink-0', collapsed ? 'px-2' : 'mx-3 mt-2', !collapsed && openGroup === 'settings' && groupPanelClass)}>
            {!collapsed ? groupHeader('settings', 'nav.preferences', SlidersHorizontal) : null}
            {collapsed || openGroup === 'settings' ? <div id="sidebar-group-settings" className={collapsed ? undefined : groupContentClass}>
            {settings?.items.map(renderLink)}
            {access ? (
              <>
                <button
                  type="button" aria-label={t('nav.group.access')} title={collapsed ? t('nav.group.access') : undefined}
                  aria-expanded={!collapsed && permissionsExpanded}
                  onClick={() => { if (collapsed) { setCollapsed(false); setOpenGroup('settings'); setAccessOpen(true); } else setAccessOpen(!permissionsExpanded); }}
                  className={cn(rowClass, 'text-slate-600 hover:bg-slate-100/80', collapsed ? 'justify-center px-0' : 'px-3')}
                >
                  <ShieldCheck className={iconClass} />
                  {!collapsed ? <><span className="flex-1 text-left">{t('nav.group.access')}</span><ChevronRight className={cn('h-3.5 w-3.5 text-slate-400 transition-transform', permissionsExpanded && 'rotate-90')} /></> : null}
                </button>
                {!collapsed && permissionsExpanded ? <div className="pl-7">{access.items.map(renderLink)}</div> : null}
              </>
            ) : null}
            </div> : null}
          </section>
        ) : null}

        {username ? (
          <div className={cn('mt-auto shrink-0 pb-3 pt-4 [@media(max-height:600px)]:py-2', collapsed ? 'px-2' : 'px-3')}>
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <button type="button" aria-label={t('user.menu')} className={cn('flex h-12 w-full items-center gap-2.5 rounded-lg text-sm hover:bg-slate-100/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed ? 'justify-center' : 'px-2.5')}>
                  <AccountAvatar account={identity} className="h-7 w-7" />
                  {!collapsed ? <><span className="min-w-0 flex-1 truncate text-left font-medium text-slate-700">{username}</span><ChevronUp className="h-3.5 w-3.5 text-slate-400" /></> : null}
                </button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content side="top" align="start" sideOffset={8} collisionPadding={12} className="z-50 w-48 rounded-xl border border-border bg-white p-1.5 text-sm text-slate-700 shadow-lg">
                  <DropdownMenu.Item onSelect={() => {
                    setMobileNavOpen(false);
                    navigate('/account', { replace: inAccount, state: inAccount ? location.state : { accountEntry: true } });
                  }} className="flex h-10 cursor-default items-center gap-2.5 rounded-md px-2.5 outline-none data-[highlighted]:bg-muted">
                    <UserRound className={iconClass} />{t('user.settings')}
                  </DropdownMenu.Item>
                  <DropdownMenu.Item onSelect={() => void handleSignOut()} className="flex h-10 cursor-default items-center gap-2.5 rounded-md px-2.5 outline-none data-[highlighted]:bg-muted">
                    <LogOut className={iconClass} />{t('user.signOut')}
                  </DropdownMenu.Item>
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          </div>
        ) : null}
      </aside>

      <div className="relative flex min-w-0 flex-1 flex-col h-full min-h-0 overflow-hidden">
        <header className={cn(
          'flex shrink-0 items-center justify-between gap-3 bg-white px-3 py-2 sm:px-6',
          onChat ? 'pointer-events-none absolute inset-x-0 top-0 z-20 min-h-16 bg-transparent' : 'min-h-14 flex-wrap border-b border-border',
        )}>
          <div className="pointer-events-auto flex items-center gap-2">
            {inAccount ? <AccountBackButton /> : <Button variant="ghost" size="icon" className="lg:hidden" aria-label={t('openNavigation')} onClick={() => { setCollapsed(false); setMobileNavOpen(true); }}>
              <Menu className="h-5 w-5" />
            </Button>}
            {!onChat && !inAccount ? <nav aria-label={t('nav.breadcrumb')} className="flex min-w-0 items-center gap-1.5 text-sm">
              {activeItem ? (
                <>
                  <span className="truncate text-slate-500">{t(activeGroup?.label ?? '')}</span>
                  <span className="text-slate-300" aria-hidden>
                    /
                  </span>
                  <span className="truncate font-medium text-slate-900">{t(activeItem.label)}</span>
                </>
              ) : (
                <span className="truncate font-medium text-slate-900">
                  {activeGroup ? t(activeGroup.label) : ''}
                </span>
              )}
            </nav> : null}
          </div>
          {!inAccount ? <div className="pointer-events-auto flex items-center gap-2">
            <LanguageSwitcher />
          </div> : null}
        </header>

        <main
          id="main-content"
          tabIndex={-1}
          className={cn(
            'min-w-0 flex-1 bg-white focus:outline-none',
            onChat || inAccount || onKnowledgeList ? 'overflow-hidden h-full min-h-0 flex flex-col' : 'overflow-auto',
          )}
        >
          <Suspense fallback={<PageSkeleton />}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  );
}
