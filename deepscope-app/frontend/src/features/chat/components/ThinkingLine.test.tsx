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
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { i18nResources } from '@/i18n/resources';
import { ThinkingLine } from './ThinkingLine';
import { ProcessMotionContext } from './processMotion';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
function wrapper({ children }: { children: ReactNode }) { return <I18nextProvider i18n={i18n}>{children}</I18nextProvider>; }
afterEach(cleanup);

describe('inline live thinking', () => {
  it('starts with one live preview line and only mounts the full process after expansion', () => {
    const { container, rerender } = render(<ThinkingLine text="First thought" streaming />, { wrapper });
    expect(container.querySelector('pre')).toBeNull();
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('false');
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('First thought');
    rerender(<ThinkingLine text={'First thought\n\nNext step'} streaming />);
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('Next step');
    expect(container.textContent).not.toContain('First thought');
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('pre')?.textContent).toBe('First thought\n\nNext step');
    expect(container.querySelector('[data-thinking-preview]')).toBeNull();
    rerender(<ThinkingLine text={'First thought\n\nNext step continues'} streaming />);
    expect(container.querySelector('pre')?.textContent).toBe('First thought\n\nNext step continues');
    fireEvent.click(screen.getByRole('button'));
    rerender(<ThinkingLine text={'First thought\n\nNext step continues\nMore'} streaming />);
    expect(container.querySelector('pre')).toBeNull();
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('More');
    rerender(<ThinkingLine text={'First thought\n\nNext step continues\nMore'} streaming={false} />);
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('false');
    expect(container.querySelector('[data-thinking-preview]')).toBeNull();
  });

  it('opens completed records inline even with a legacy inspect callback and preserves manual expansion', () => {
    const onInspect = vi.fn();
    const { container, rerender } = render(<ThinkingLine text="Thought" streaming={false} onInspect={onInspect} />, { wrapper });
    expect(container.querySelector('pre')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('pre')?.textContent).toBe('Thought');
    expect(screen.getByRole('button').getAttribute('aria-haspopup')).toBeNull();
    expect(onInspect).not.toHaveBeenCalled();
    rerender(<ThinkingLine text="Thought grows" streaming onInspect={onInspect} />);
    rerender(<ThinkingLine text="Thought done" streaming={false} onInspect={onInspect} />);
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('true');
  });

  it('keeps untouched reasoning collapsed when thinking completes', () => {
    const { rerender, container } = render(<ThinkingLine text="Actual thought" streaming />, { wrapper });
    rerender(<ThinkingLine text="Actual thought" streaming={false} />);
    expect(container.querySelector('pre')).toBeNull();
    expect(container.querySelector('.process-status-live')).toBeNull();
    expect(container.querySelector('[data-process-loading]')).toBeNull();
  });

  it('keeps paused or background thinking readable without activity animation', () => {
    const { container } = render(<ProcessMotionContext.Provider value={false}><ThinkingLine text="Actual thought" streaming /></ProcessMotionContext.Provider>, { wrapper });
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('Actual thought');
    expect(container.querySelector('pre')).toBeNull();
    expect(container.querySelector('.thinking-preview-live')).toBeNull();
    expect(container.querySelector('.process-status-live')).toBeNull();
    expect(container.querySelector('[data-process-loading]')).toBeNull();
  });

  it('previews the latest nonempty line and retains all formatting in the expanded process', () => {
    const text = 'Earlier thought\r\n\r\n### **Check permissions**\r\n\r\n';
    const { container } = render(<ThinkingLine text={text} streaming />, { wrapper });
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('Check permissions');
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('pre')?.textContent).toBe(text);
  });

  it('follows new characters within the same single-line preview', () => {
    const { container, rerender } = render(<ThinkingLine text="Reviewing" streaming />, { wrapper });
    const preview = container.querySelector<HTMLElement>('[data-thinking-preview]')!;
    Object.defineProperty(preview, 'scrollWidth', { value: 800, configurable: true });
    rerender(<ThinkingLine text="Reviewing the streamed context and current actions" streaming />);
    expect(container.querySelector('[data-thinking-preview]')).toBe(preview);
    expect(preview.scrollLeft).toBe(800);
    expect(preview.textContent).toBe('Reviewing the streamed context and current actions');
    expect(container.querySelector('pre')).toBeNull();
  });
});
