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
import { afterEach, describe, expect, it } from 'vitest';
import type { ReactNode } from 'react';
import { i18nResources } from '@/i18n/resources';
import { ProcessText } from './ProcessText';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', fallbackLng: 'en', defaultNS: 'app', react: { useSuspense: false } });
function wrapper({ children }: { children: ReactNode }) { return <I18nextProvider i18n={i18n}>{children}</I18nextProvider>; }

describe('bounded process text', () => {
  afterEach(cleanup);
  it('does not split a supplementary Unicode character across page boundaries', () => {
    const { container } = render(<ProcessText text={'甲'.repeat(11999) + '😀尾'} />, { wrapper });
    expect(container.querySelector('pre')?.textContent?.endsWith('😀')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Next portion' }));
    expect(container.querySelector('pre')?.textContent).toBe('尾');
  });

  it('does not create an empty last page when a complete character extends the first page', () => {
    render(<ProcessText text={'甲'.repeat(11999) + '😀'} />, { wrapper });
    expect(screen.queryByRole('button', { name: 'Next portion' })).toBeNull();
  });

  it('keeps the selected page during streaming and clamps it when content becomes shorter', () => {
    const { container, rerender } = render(<ProcessText text={'a'.repeat(12000) + 'second'} />, { wrapper });
    fireEvent.click(screen.getByRole('button', { name: 'Next portion' }));
    rerender(<ProcessText text={'a'.repeat(12000) + 'second grows'} />);
    expect(container.querySelector('pre')?.textContent).toBe('second grows');
    rerender(<ProcessText text="short" />);
    expect(container.querySelector('pre')?.textContent).toBe('short');
    expect(screen.queryByRole('button', { name: 'Next portion' })).toBeNull();
    rerender(<ProcessText text={'a'.repeat(12000) + 'new second'} />);
    expect(container.querySelector('pre')?.textContent?.charAt(0)).toBe('a');
  });

  it('follows the newest live portion while keeping earlier portions available', () => {
    const { container, rerender } = render(<ProcessText text={'a'.repeat(12000) + 'latest'} live />, { wrapper });
    expect(container.querySelector('pre')?.textContent).toBe('latest');
    rerender(<ProcessText text={'a'.repeat(12000) + 'b'.repeat(12000) + 'newest'} live />);
    expect(container.querySelector('pre')?.textContent).toBe('newest');
    fireEvent.click(screen.getByRole('button', { name: 'Previous portion' }));
    rerender(<ProcessText text={'a'.repeat(12000) + 'b'.repeat(12000) + 'newest grows'} live />);
    expect(container.querySelector('pre')?.textContent).toBe('b'.repeat(12000));
    fireEvent.click(screen.getByRole('button', { name: 'Previous portion' }));
    expect(container.querySelector('pre')?.textContent).toBe('a'.repeat(12000));
    fireEvent.click(screen.getByRole('button', { name: 'Next portion' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next portion' }));
    rerender(<ProcessText text={'a'.repeat(12000) + 'b'.repeat(12000) + 'c'.repeat(12000) + 'end'} live />);
    expect(container.querySelector('pre')?.textContent).toBe('end');
  });

  it('stops following when reading above the bottom and resumes at the latest bottom', () => {
    const { container, rerender } = render(<ProcessText text="one" live />, { wrapper });
    const pre = container.querySelector('pre')!;
    Object.defineProperties(pre, { scrollHeight: { value: 600, configurable: true }, clientHeight: { value: 200, configurable: true } });
    rerender(<ProcessText text="one two" live />);
    expect(pre.scrollTop).toBe(400);
    pre.scrollTop = 100;
    fireEvent.scroll(pre);
    rerender(<ProcessText text={'x'.repeat(12000) + 'new page'} live />);
    expect(pre.scrollTop).toBe(100);
    expect(pre.textContent).toBe('x'.repeat(12000));
    fireEvent.click(screen.getByRole('button', { name: 'Next portion' }));
    expect(pre.scrollTop).toBe(400);
    pre.scrollTop = 200;
    fireEvent.scroll(pre);
    rerender(<ProcessText text={'x'.repeat(12000) + 'new page grows'} live />);
    expect(pre.scrollTop).toBe(200);
    pre.scrollTop = 400;
    fireEvent.scroll(pre);
    rerender(<ProcessText text={'x'.repeat(12000) + 'y'.repeat(12000) + 'latest again'} live />);
    expect(pre.textContent).toBe('latest again');
    expect(pre.scrollTop).toBe(400);
  });
});
