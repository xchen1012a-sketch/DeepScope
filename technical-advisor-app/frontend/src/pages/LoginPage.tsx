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

import { useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Eye, EyeOff, Globe, LockKeyhole, UserRound } from 'lucide-react';
import { login } from '@/api/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { BrandLogo } from '@/components/ui/BrandLogo';
import './LoginPage.css';

export default function LoginPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const usernameInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const usernameMissing = submitted && !username.trim();
  const passwordMissing = submitted && !password;
  const isChinese = i18n.language.startsWith('zh');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;
    setSubmitted(true);
    setError(false);
    if (!username.trim() || !password) {
      (!username.trim() ? usernameInput : passwordInput).current?.focus();
      return;
    }
    setLoading(true);
    try {
      await login(username.trim(), password);
      navigate('/', { replace: true });
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }

  return <main className="login-screen">
    <div className="login-layout">
      <div className="login-hero-art" aria-hidden="true">
        <img src="/login/approved-reference.png" alt="" draggable={false} />
      </div>
      <button type="button" className="login-language"
        onClick={() => void i18n.changeLanguage(isChinese ? 'en' : 'zh-CN')}>
        <Globe aria-hidden="true" />
        <span>{t(isChinese ? 'login.langZh' : 'login.langEn')}</span>
      </button>
      <header className="login-brand">
        <BrandLogo className="login-brand-mark rounded-none bg-transparent ring-0" iconClassName="h-full w-full" />
        <div>
          <p className="login-wordmark">DeepScope</p>
          <p className="login-tagline">{t('login.tagline')}</p>
        </div>
      </header>
      <form className="login-card" noValidate onSubmit={handleSubmit} aria-labelledby="login-title" aria-busy={loading}>
        <div className="login-card-brand">
          <BrandLogo className="login-card-mark rounded-none bg-transparent ring-0" iconClassName="h-full w-full" />
          <h1 id="login-title">{t('login.title')}</h1>
          <p>{t('login.subtitle')}</p>
        </div>
        <div className="login-fields">
          <div className="login-field">
            <label htmlFor="login-username">{t('login.username')}</label>
            <div className="login-input-wrap">
              <UserRound aria-hidden="true" className="login-input-icon" />
              <Input id="login-username" ref={usernameInput} type="text" autoComplete="username" required disabled={loading}
                aria-invalid={usernameMissing} aria-describedby={usernameMissing ? 'login-username-error' : undefined}
                value={username} onChange={event => setUsername(event.target.value)}
                placeholder={t('login.usernamePlaceholder')} className="login-input" />
            </div>
            {usernameMissing && <p id="login-username-error" role="alert" className="login-field-error">{t('login.usernamePlaceholder')}</p>}
          </div>
          <div className="login-field">
            <label htmlFor="login-password">{t('login.password')}</label>
            <div className="login-input-wrap">
              <LockKeyhole aria-hidden="true" className="login-input-icon" />
              <Input id="login-password" ref={passwordInput} type={showPassword ? 'text' : 'password'} autoComplete="current-password" required disabled={loading}
                aria-invalid={passwordMissing} aria-describedby={passwordMissing ? 'login-password-error' : undefined}
                value={password} onChange={event => setPassword(event.target.value)}
                placeholder={t('login.passwordPlaceholder')} className="login-input login-password" />
              <button type="button" className="login-password-toggle" disabled={loading}
                aria-label={t(showPassword ? 'login.hidePassword' : 'login.showPassword')}
                aria-controls="login-password" aria-pressed={showPassword}
                onClick={() => setShowPassword(value => !value)}>
                {showPassword ? <Eye aria-hidden="true" /> : <EyeOff aria-hidden="true" />}
              </button>
            </div>
            {passwordMissing && <p id="login-password-error" role="alert" className="login-field-error">{t('login.passwordPlaceholder')}</p>}
          </div>
        </div>
        {error && <p role="alert" className="login-error">{t('login.errorInvalid')}</p>}
        <Button type="submit" disabled={loading} className="login-submit">
          <span>{t(loading ? 'login.signingIn' : 'login.signIn')}</span>
          {!loading && <ArrowRight aria-hidden="true" />}
        </Button>
      </form>
    </div>
  </main>;
}
