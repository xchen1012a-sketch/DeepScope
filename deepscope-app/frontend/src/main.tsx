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

import React from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, createRoutesFromElements, Navigate, Route, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider, useTranslation } from 'react-i18next';
import './index.css';

import i18n from './i18n';
import AppShell from './app/AppShell';
import { PrivateRoute } from './app/PrivateRoute';

const LoginPage = React.lazy(() => import('./pages/LoginPage'));
const RegisterPage = React.lazy(() => import('./pages/RegisterPage'));
const ChatPage = React.lazy(() => import('./features/chat/ChatPage'));
const AgentPage = React.lazy(() => import('./features/agent/AgentPage'));
const ToolsPage = React.lazy(() => import('./features/tool/ToolsPage'));
const SkillsPage = React.lazy(() => import('./features/skill/SkillsPage'));
const KnowledgePage = React.lazy(() => import('./features/knowledge/KnowledgePage'));
const SettingsPage = React.lazy(() => import('./features/settings/SettingsPage'));
const UsersPage = React.lazy(() => import('./features/access/UsersPage'));
const RolesPage = React.lazy(() => import('./features/access/RolesPage'));
const AccountPage = React.lazy(() => import('./features/account/AccountPage'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function LoadingFallback() {
  const { t } = useTranslation('common');
  return (
    <div className="flex h-full items-center justify-center text-sm text-muted-foreground">{t('loading')}</div>
  );
}

const router = createBrowserRouter(createRoutesFromElements(<>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />

              <Route
                element={
                  <PrivateRoute>
                    <AppShell />
                  </PrivateRoute>
                }
              >
                <Route path="/" element={<Navigate to="/chat" replace />} />
                    <Route path="/chat" element={<ChatPage />} />
                    <Route path="/chat/:sessionId" element={<ChatPage />} />
                    <Route path="/agent" element={<AgentPage />} />
                    <Route path="/agent/new" element={<AgentPage />} />
                    <Route path="/agent/creation/:draftId" element={<AgentPage />} />
                    <Route path="/agent/:agentId" element={<AgentPage />} />
                <Route path="/tools" element={<ToolsPage />} />
                <Route path="/skills" element={<SkillsPage />} />
                <Route path="/knowledge" element={<KnowledgePage />} />
                <Route path="/knowledge/:libraryId" element={<KnowledgePage />} />
                <Route path="/settings" element={<SettingsPage />} />
                <Route path="/account/*" element={<AccountPage />} />
                <Route path="/access" element={<Navigate to="/access/users" replace />} />
                <Route path="/access/users" element={<UsersPage />} />
                <Route path="/access/roles" element={<RolesPage />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Route>

</>), {
  future: {
    v7_relativeSplatPath: true,
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <React.Suspense fallback={<LoadingFallback />}>
          <RouterProvider router={router} future={{ v7_startTransition: true }} />
        </React.Suspense>
      </QueryClientProvider>
    </I18nextProvider>
  </React.StrictMode>,
);
