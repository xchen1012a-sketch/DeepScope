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
import { DataTable, type DataTableColumn } from './DataTable';

afterEach(cleanup);

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'zh-CN',
  fallbackLng: 'zh-CN',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
});

interface Item {
  id: string;
  name: string;
  status: string;
}

const columns: DataTableColumn<Item>[] = [
  {
    id: 'name',
    header: '名称',
    cell: (item) => <span>{item.name}</span>,
  },
  {
    id: 'status',
    header: '状态',
    cell: (item) => <span>{item.status}</span>,
  },
];

const mockData: Item[] = [
  { id: '1', name: 'Item 1', status: 'Active' },
  { id: '2', name: 'Item 2', status: 'Draft' },
];

function renderWithI18n(ui: React.ReactElement) {
  return render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);
}

describe('DataTable', () => {
  it('renders loading skeleton when isLoading is true', () => {
    renderWithI18n(
      <DataTable
        columns={columns}
        data={[]}
        keyExtractor={(item) => item.id}
        isLoading={true}
      />,
    );

    expect(screen.getByTestId('data-table-loading')).toBeTruthy();
  });

  it('renders empty state when data is empty and not loading', () => {
    renderWithI18n(
      <DataTable
        columns={columns}
        data={[]}
        keyExtractor={(item) => item.id}
        emptyTitle="暂无任何数据"
      />,
    );

    expect(screen.getByText('暂无任何数据')).toBeTruthy();
  });

  it('renders table headers and row items', () => {
    renderWithI18n(
      <DataTable
        columns={columns}
        data={mockData}
        keyExtractor={(item) => item.id}
      />,
    );

    expect(screen.getByText('名称')).toBeTruthy();
    expect(screen.getByText('状态')).toBeTruthy();
    expect(screen.getByText('Item 1')).toBeTruthy();
    expect(screen.getByText('Item 2')).toBeTruthy();
  });

  it('supports pagination and page size switching', () => {
    const onPageChange = vi.fn();
    const onPageSizeChange = vi.fn();

    renderWithI18n(
      <DataTable
        columns={columns}
        data={mockData}
        keyExtractor={(item) => item.id}
        page={1}
        pageSize={10}
        total={25}
        onPageChange={onPageChange}
        pageSizeOptions={[10, 20, 50]}
        onPageSizeChange={onPageSizeChange}
      />,
    );

    expect(screen.getByTestId('pagination')).toBeTruthy();
    expect(screen.getByText(/共 25 (条|项)/)).toBeTruthy();

    // Click next page
    const nextBtn = screen.getByRole('button', { name: '下一页' });
    fireEvent.click(nextBtn);
    expect(onPageChange).toHaveBeenCalledWith(2);
  });
});
