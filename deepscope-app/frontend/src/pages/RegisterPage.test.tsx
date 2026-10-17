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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { getRegistrationConfig, register } from '@/api/auth';
import RegisterPage from './RegisterPage';

vi.mock('@/api/auth', () => ({
  register: vi.fn(),
  getRegistrationConfig: vi.fn(() => Promise.resolve({ enabled: true, minPasswordLength: 8 })),
}));

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

async function mount() {
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({
    resources: i18nResources,
    lng: 'zh-CN',
    defaultNS: 'app',
    interpolation: { escapeValue: false },
  });
  render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/" element={<p>进入主页</p>} />
          <Route path="/login" element={<p>登录页面</p>} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  );
  return userEvent.setup();
}

describe('RegisterPage', () => {
  it('validates required fields, email format, and password length', async () => {
    const user = await mount();
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(register).not.toHaveBeenCalled();
    expect(screen.getByText('请输入邮箱地址')).toBeTruthy();

    // Invalid email format
    await user.type(screen.getByLabelText('邮箱'), 'invalid-email');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(register).not.toHaveBeenCalled();
    expect(screen.getByText('请输入有效的邮箱地址')).toBeTruthy();

    // Fix email, submit with empty password
    await user.clear(screen.getByLabelText('邮箱'));
    await user.type(screen.getByLabelText('邮箱'), 'test@example.com');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(screen.getByText('请输入密码')).toBeTruthy();

    // Password too short (< 8 chars)
    await user.type(screen.getByLabelText('密码', { exact: true }), '12345');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(screen.getByText('密码长度不能少于 8 位')).toBeTruthy();

    // Fix password, missing confirm password
    await user.clear(screen.getByLabelText('密码', { exact: true }));
    await user.type(screen.getByLabelText('密码', { exact: true }), 'password123');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(screen.getByText('请再次输入密码')).toBeTruthy();

    // Passwords mismatch
    await user.type(screen.getByLabelText('确认密码'), 'password456');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));
    expect(screen.getByText('两次输入的密码不一致')).toBeTruthy();
  });

  it('registers successfully and navigates to destination', async () => {
    vi.mocked(register).mockResolvedValue({ token: 'tok', userId: 'u1', username: 'test@example.com', roles: ['user'] });
    const user = await mount();

    await user.type(screen.getByLabelText('邮箱'), ' Test@Example.COM ');
    await user.type(screen.getByLabelText('密码', { exact: true }), 'password123');
    await user.type(screen.getByLabelText('确认密码'), 'password123');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));

    await waitFor(() => expect(register).toHaveBeenCalledWith('test@example.com', 'password123'));
    expect(await screen.findByText('进入主页')).toBeTruthy();
  });

  it('displays conflict error when email is already registered', async () => {
    const error = new Error('Conflict') as any;
    error.status = 409;
    vi.mocked(register).mockRejectedValue(error);
    const user = await mount();

    await user.type(screen.getByLabelText('邮箱'), 'existing@example.com');
    await user.type(screen.getByLabelText('密码', { exact: true }), 'password123');
    await user.type(screen.getByLabelText('确认密码'), 'password123');
    await user.click(screen.getByRole('button', { name: '注册并登录' }));

    expect(await screen.findByText('该邮箱已被注册')).toBeTruthy();
  });

  it('shows closed notice and disables submit when registration is disabled', async () => {
    vi.mocked(getRegistrationConfig).mockResolvedValueOnce({ enabled: false, minPasswordLength: 8 });
    await mount();

    expect(await screen.findByText('系统暂未开放注册')).toBeTruthy();
    expect(screen.getByRole('button', { name: '注册并登录' })).toHaveProperty('disabled', true);
  });

  it('supports language toggle and navigation to login', async () => {
    const user = await mount();
    await user.click(screen.getByRole('button', { name: /简体中文/ }));
    expect(await screen.findByText('Create an account to get started')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Register & Sign in' })).toBeTruthy();

    const loginLink = screen.getByRole('link', { name: 'Sign in' });
    expect(loginLink.getAttribute('href')).toBe('/login');
  });
});
