/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { login, getRegistrationConfig } from '@/api/auth';
import LoginPage from './LoginPage';

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getRegistrationConfig: vi.fn(() => Promise.resolve({ enabled: false, minPasswordLength: 8 })),
}));
afterEach(() => { cleanup(); vi.resetAllMocks(); });
async function mount() {
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'zh-CN', defaultNS: 'app', interpolation: { escapeValue: false } });
  render(<I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/login']}><Routes><Route path="/login" element={<LoginPage />} /><Route path="/" element={<p>已进入工作空间</p>} /></Routes></MemoryRouter></I18nextProvider>);
  return userEvent.setup();
}

it('keeps empty credentials local and announces invalid login without losing the form', async () => {
  vi.mocked(login).mockRejectedValue(new Error('invalid credentials'));
  const user = await mount();
  await user.click(screen.getByRole('button', { name: '登录' }));
  expect(login).not.toHaveBeenCalled();
  expect(screen.getAllByRole('alert')).toHaveLength(2);
  expect(screen.getByLabelText('账号')).toBe(document.activeElement);
  expect(screen.getByPlaceholderText('请输入账号')).toBeTruthy();
  await user.type(screen.getByLabelText('账号'), 'operator');
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  await user.type(screen.getByLabelText('密码', { exact: true }), 'fake-password');
  await user.click(screen.getByRole('button', { name: '登录' }));
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', '账号或密码错误');
});

it('preserves password visibility, language switching and successful login navigation', async () => {
  vi.mocked(login).mockResolvedValue({ token: 'fake', userId: 'u', username: 'operator', roles: [] });
  const user = await mount();
  await user.type(screen.getByLabelText('账号'), 'operator');
  await user.type(screen.getByLabelText('密码', { exact: true }), 'fake-password');
  await user.click(screen.getByRole('button', { name: '显示密码' }));
  expect(screen.getByLabelText('密码', { exact: true })).toHaveProperty('type', 'text');
  await user.click(screen.getByRole('button', { name: /简体中文/ }));
  expect(await screen.findByText('Welcome back, keep exploring')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  await waitFor(() => expect(login).toHaveBeenCalledWith('operator', 'fake-password'));
  expect(await screen.findByText('已进入工作空间')).toBeTruthy();
});

it('shows registration entry when registration is enabled', async () => {
  vi.mocked(getRegistrationConfig).mockResolvedValueOnce({ enabled: true, minPasswordLength: 8 });
  await mount();
  expect(await screen.findByRole('link', { name: '立即注册' })).toBeTruthy();
  expect(screen.getByRole('link', { name: '立即注册' }).getAttribute('href')).toBe('/register');
});

it('does not show registration entry when registration is disabled', async () => {
  vi.mocked(getRegistrationConfig).mockResolvedValueOnce({ enabled: false, minPasswordLength: 8 });
  await mount();
  await waitFor(() => expect(screen.queryByRole('link', { name: '立即注册' })).toBeNull());
});
