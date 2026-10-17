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

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Eye, EyeOff, Globe, LockKeyhole, Mail } from 'lucide-react';
import { getRegistrationConfig, register } from '@/api/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { BrandLogo } from '@/components/ui/BrandLogo';
import './LoginPage.css';

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export default function RegisterPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [minPasswordLength, setMinPasswordLength] = useState(8);
  const [registrationEnabled, setRegistrationEnabled] = useState<boolean | null>(null);

  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const emailInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmPasswordInput = useRef<HTMLInputElement>(null);

  const isChinese = i18n.language.startsWith('zh');

  useEffect(() => {
    let active = true;
    void getRegistrationConfig()
      .then((cfg) => {
        if (!active) return;
        setRegistrationEnabled(cfg.enabled);
        if (cfg.minPasswordLength) {
          setMinPasswordLength(cfg.minPasswordLength);
        }
      })
      .catch(() => {
        if (active) setRegistrationEnabled(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const normalizedEmail = email.trim().toLowerCase();
  const emailMissing = submitted && !normalizedEmail;
  const emailInvalid = submitted && Boolean(normalizedEmail) && !EMAIL_PATTERN.test(normalizedEmail);
  const passwordMissing = submitted && !password;
  const passwordTooShort = submitted && Boolean(password) && password.length < minPasswordLength;
  const confirmPasswordMissing = submitted && !confirmPassword;
  const passwordMismatch = submitted && Boolean(confirmPassword) && password !== confirmPassword;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;
    setSubmitted(true);
    setServerError(null);

    if (registrationEnabled === false) {
      setServerError(t('register.disabled'));
      return;
    }

    if (!normalizedEmail) {
      emailInput.current?.focus();
      return;
    }
    if (!EMAIL_PATTERN.test(normalizedEmail)) {
      emailInput.current?.focus();
      return;
    }
    if (!password) {
      passwordInput.current?.focus();
      return;
    }
    if (password.length < minPasswordLength) {
      passwordInput.current?.focus();
      return;
    }
    if (!confirmPassword) {
      confirmPasswordInput.current?.focus();
      return;
    }
    if (password !== confirmPassword) {
      confirmPasswordInput.current?.focus();
      return;
    }

    setLoading(true);
    try {
      await register(normalizedEmail, password);
      navigate('/', { replace: true });
    } catch (err: unknown) {
      const errorObj = err as {
        status?: number;
        response?: { status?: number; data?: { error?: string; message?: string } };
      };
      const status = errorObj.status ?? errorObj.response?.status;
      if (status === 409) {
        setServerError(t('register.errorDuplicate'));
      } else if (status === 403) {
        setServerError(t('register.disabled'));
      } else {
        setServerError(t('register.errorGeneric'));
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-screen">
      <div className="login-layout">
        <div className="login-hero-art" aria-hidden="true">
          <img src="/images/login-illustration.png" alt="" draggable={false} />
        </div>
        <button
          type="button"
          className="login-language"
          onClick={() => void i18n.changeLanguage(isChinese ? 'en' : 'zh-CN')}
        >
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

        <form className="login-card" noValidate onSubmit={handleSubmit} aria-labelledby="register-title" aria-busy={loading}>
          <div className="login-card-brand">
            <BrandLogo className="login-card-mark rounded-none bg-transparent ring-0" iconClassName="h-full w-full" />
            <h1 id="register-title">{t('register.title')}</h1>
            <p>{t('register.subtitle')}</p>
          </div>

          <div className="login-fields">
            {/* Email */}
            <div className="login-field">
              <label htmlFor="register-email">{t('register.email')}</label>
              <div className="login-input-wrap">
                <Mail aria-hidden="true" className="login-input-icon" />
                <Input
                  id="register-email"
                  ref={emailInput}
                  type="email"
                  autoComplete="email"
                  required
                  disabled={loading}
                  aria-invalid={emailMissing || emailInvalid}
                  aria-describedby={
                    emailMissing ? 'register-email-error' : emailInvalid ? 'register-email-format-error' : undefined
                  }
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder={t('register.emailPlaceholder')}
                  className="login-input"
                />
              </div>
              {emailMissing && (
                <p id="register-email-error" role="alert" className="login-field-error">
                  {t('register.errorEmailMissing')}
                </p>
              )}
              {!emailMissing && emailInvalid && (
                <p id="register-email-format-error" role="alert" className="login-field-error">
                  {t('register.errorEmailInvalid')}
                </p>
              )}
            </div>

            {/* Password */}
            <div className="login-field">
              <label htmlFor="register-password">{t('register.password')}</label>
              <div className="login-input-wrap">
                <LockKeyhole aria-hidden="true" className="login-input-icon" />
                <Input
                  id="register-password"
                  ref={passwordInput}
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  required
                  disabled={loading}
                  aria-invalid={passwordMissing || passwordTooShort}
                  aria-describedby={
                    passwordMissing ? 'register-password-error' : passwordTooShort ? 'register-password-len-error' : undefined
                  }
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={t('register.passwordPlaceholder')}
                  className="login-input login-password"
                />
                <button
                  type="button"
                  className="login-password-toggle"
                  disabled={loading}
                  aria-label={t(showPassword ? 'register.hidePassword' : 'register.showPassword')}
                  aria-controls="register-password"
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((v) => !v)}
                >
                  {showPassword ? <Eye aria-hidden="true" /> : <EyeOff aria-hidden="true" />}
                </button>
              </div>
              {passwordMissing && (
                <p id="register-password-error" role="alert" className="login-field-error">
                  {t('register.errorPasswordMissing')}
                </p>
              )}
              {!passwordMissing && passwordTooShort && (
                <p id="register-password-len-error" role="alert" className="login-field-error">
                  {t('register.errorPasswordLength', { min: minPasswordLength })}
                </p>
              )}
            </div>

            {/* Confirm Password */}
            <div className="login-field">
              <label htmlFor="register-confirm-password">{t('register.confirmPassword')}</label>
              <div className="login-input-wrap">
                <LockKeyhole aria-hidden="true" className="login-input-icon" />
                <Input
                  id="register-confirm-password"
                  ref={confirmPasswordInput}
                  type={showConfirmPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  required
                  disabled={loading}
                  aria-invalid={confirmPasswordMissing || passwordMismatch}
                  aria-describedby={
                    confirmPasswordMissing
                      ? 'register-confirm-error'
                      : passwordMismatch
                      ? 'register-mismatch-error'
                      : undefined
                  }
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder={t('register.confirmPasswordPlaceholder')}
                  className="login-input login-password"
                />
                <button
                  type="button"
                  className="login-password-toggle"
                  disabled={loading}
                  aria-label={t(showConfirmPassword ? 'register.hidePassword' : 'register.showPassword')}
                  aria-controls="register-confirm-password"
                  aria-pressed={showConfirmPassword}
                  onClick={() => setShowConfirmPassword((v) => !v)}
                >
                  {showConfirmPassword ? <Eye aria-hidden="true" /> : <EyeOff aria-hidden="true" />}
                </button>
              </div>
              {confirmPasswordMissing && (
                <p id="register-confirm-error" role="alert" className="login-field-error">
                  {t('register.errorConfirmPasswordMissing')}
                </p>
              )}
              {!confirmPasswordMissing && passwordMismatch && (
                <p id="register-mismatch-error" role="alert" className="login-field-error">
                  {t('register.errorPasswordMismatch')}
                </p>
              )}
            </div>
          </div>

          {serverError && (
            <p role="alert" className="login-error">
              {serverError}
            </p>
          )}

          {registrationEnabled === false && (
            <p role="alert" className="login-error">
              {t('register.disabled')}
            </p>
          )}

          <Button type="submit" disabled={loading || registrationEnabled === false} className="login-submit">
            <span>{t(loading ? 'register.submitting' : 'register.submit')}</span>
            {!loading && <ArrowRight aria-hidden="true" />}
          </Button>

          <div className="login-switch-action">
            <span>{t('register.hasAccount')}</span>
            <Link to="/login">{t('register.goToLogin')}</Link>
          </div>
        </form>
      </div>
    </main>
  );
}
