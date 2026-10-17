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
import { i18nResources } from '@/i18n/resources';
import { Pagination } from './Pagination';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'zh-CN',
  fallbackLng: 'zh-CN',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

function renderPagination(props: {
  page: number;
  pageSize: number;
  total: number;
  onChange: (page: number) => void;
}) {
  return render(
    <I18nextProvider i18n={i18n}>
      <Pagination {...props} />
    </I18nextProvider>,
  );
}

describe('Pagination', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders pagination info and page buttons for small total count', () => {
    const onChange = vi.fn();
    renderPagination({ page: 1, pageSize: 10, total: 25, onChange });

    // Expect page info: 第 1 / 3 页 · 共 25 条
    expect(screen.getByText(/共 25 条/)).toBeTruthy();

    const prevBtn = screen.getByRole('button', { name: /上一页/i }) as HTMLButtonElement;
    const nextBtn = screen.getByRole('button', { name: /下一页/i }) as HTMLButtonElement;

    expect(prevBtn.disabled).toBe(true);
    expect(nextBtn.disabled).toBe(false);

    // Check page numbers 1, 2, 3
    expect(screen.getByRole('button', { name: '1' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '2' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '3' })).toBeTruthy();

    // Click next
    fireEvent.click(nextBtn);
    expect(onChange).toHaveBeenCalledWith(2);

    // Click specific page
    fireEvent.click(screen.getByRole('button', { name: '3' }));
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it('renders disabled next button when on last page', () => {
    const onChange = vi.fn();
    renderPagination({ page: 3, pageSize: 10, total: 30, onChange });

    const prevBtn = screen.getByRole('button', { name: /上一页/i }) as HTMLButtonElement;
    const nextBtn = screen.getByRole('button', { name: /下一页/i }) as HTMLButtonElement;

    expect(prevBtn.disabled).toBe(false);
    expect(nextBtn.disabled).toBe(true);

    fireEvent.click(prevBtn);
    expect(onChange).toHaveBeenCalledWith(2);
  });

  it('renders ellipsis for large number of pages', () => {
    const onChange = vi.fn();
    renderPagination({ page: 5, pageSize: 10, total: 100, onChange });

    expect(screen.getAllByText('…').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '1' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '10' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '5' })).toBeTruthy();
  });

  it('renders page size selector and triggers onPageSizeChange', async () => {
    const onChange = vi.fn();
    const onPageSizeChange = vi.fn();

    render(
      <I18nextProvider i18n={i18n}>
        <Pagination
          page={1}
          pageSize={10}
          total={50}
          onChange={onChange}
          pageSizeOptions={[10, 20, 50]}
          onPageSizeChange={onPageSizeChange}
        />
      </I18nextProvider>,
    );

    const selectBtn = screen.getByRole('button', { name: '10 条/页' });
    expect(selectBtn).toBeTruthy();

    fireEvent.pointerDown(selectBtn, { button: 0 });
    fireEvent.keyDown(selectBtn, { key: 'ArrowDown' });

    const option20 = await screen.findByRole('menuitem', { name: '20 条/页' });
    fireEvent.click(option20);

    expect(onPageSizeChange).toHaveBeenCalledWith(20);
  });
});
