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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ApiError } from '@/lib/apiClient';
import * as toolApi from '@/api/tool';
import { ToolTestPanel } from './ToolTestPanel';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

if (typeof window !== 'undefined' && window.CustomEvent) {
  globalThis.CustomEvent = window.CustomEvent;
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </I18nextProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const mockTool: toolApi.CatalogTool = {
  toolId: 'tool-test-1',
  name: 'WeatherService',
  description: 'Fetches weather info',
  origin: 'http',
  permission: 'allow',
  canEdit: true,
  approved: true,
  revision: 2,
  ownerUserId: 'user-1',
  mcpServerId: null,
  httpMethod: 'GET',
  httpUrl: 'https://api.weather.com/v1',
  inputSchema: JSON.stringify({
    type: 'object',
    properties: {
      city: { type: 'string', default: 'Beijing' },
    },
  }),
};

describe('ToolTestPanel', () => {
  it('preserves numeric examples and enum values in the weather parameter template', () => {
    renderWithProviders(<ToolTestPanel tool={{ ...mockTool, name: 'get_current_temperature', inputSchema: JSON.stringify({ type: 'object', properties: {
      latitude: { type: 'number', example: 39.9042 }, longitude: { type: 'number', example: 116.4074 },
      current: { type: 'string', enum: ['temperature_2m'] },
    }, required: ['latitude', 'longitude', 'current'] }) }} open onClose={vi.fn()} />);
    expect(JSON.parse((screen.getByLabelText(/Input Parameters/i) as HTMLTextAreaElement).value)).toEqual({ latitude: 39.9042, longitude: 116.4074, current: 'temperature_2m' });
  });

  it('locks input while AI fills parameters and keeps the original input on failure', async () => {
    let fail!: (error: Error) => void;
    const fill = vi.spyOn(toolApi, 'suggestToolTestInput').mockImplementation(() => new Promise((_resolve, reject) => { fail = reject; }));
    renderWithProviders(<ToolTestPanel tool={mockTool} open onClose={vi.fn()} />);
    const input = screen.getByLabelText(/Input Parameters/i) as HTMLTextAreaElement;
    const original = input.value;
    fireEvent.click(screen.getByRole('button', { name: 'AI autofill' }));
    await waitFor(() => expect(input.disabled).toBe(true));
    expect((screen.getByRole('button', { name: /Run Test/i }) as HTMLButtonElement).disabled).toBe(true);
    expect(fill).toHaveBeenCalled();
    fail(new Error('model unavailable'));
    await screen.findByRole('alert');
    expect(input.value).toBe(original);
    expect(input.disabled).toBe(false);
  });

  it('fills generated JSON without running the tool and ignores a response after closing', async () => {
    let finish!: (value: { input: Record<string, unknown> }) => void;
    vi.spyOn(toolApi, 'suggestToolTestInput').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const run = vi.spyOn(toolApi, 'testTool');
    const view = renderWithProviders(<ToolTestPanel tool={mockTool} open onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'AI autofill' }));
    await waitFor(() => expect(screen.getByLabelText(/Input Parameters/i).matches(':disabled')).toBe(true));
    finish({ input: { city: 'Shanghai' } });
    await waitFor(() => expect((screen.getByLabelText(/Input Parameters/i) as HTMLTextAreaElement).value).toContain('Shanghai'));
    expect(run).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'AI autofill' }));
    view.unmount();
    renderWithProviders(<ToolTestPanel tool={mockTool} open onClose={vi.fn()} />);
    finish({ input: { city: 'Must not overwrite' } });
    await waitFor(() => expect((screen.getByLabelText(/Input Parameters/i) as HTMLTextAreaElement).value).toContain('Beijing'));
  });

  it('shows a remote failure as an error while preserving its diagnostic result', async () => {
    vi.spyOn(toolApi, 'testTool').mockResolvedValue({ httpStatus: 400, elapsedMs: 10, errorCode: 'remote_http_error', messageKey: 'tool.errors.remote_http_error', result: 'invalid query' });
    renderWithProviders(<ToolTestPanel tool={mockTool} open onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /Run Test/i }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('invalid query')).toBeTruthy();
  });
  it('renders tool info, prefilled schema parameters, and runs test successfully', async () => {
    const testToolSpy = vi.spyOn(toolApi, 'testTool').mockResolvedValue({
      httpStatus: 200,
      elapsedMs: 145,
      result: '{"temperature": 22}',
      truncated: false,
    });
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    expect(screen.getByText(/WeatherService/)).toBeTruthy();
    expect(screen.getByText(/GET https:\/\/api.weather.com\/v1/)).toBeTruthy();

    const textarea = screen.getByLabelText(/Input Parameters/i) as HTMLTextAreaElement;
    expect(textarea.value).toContain('"city": "Beijing"');

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(testToolSpy).toHaveBeenCalledTimes(1);
    });

    expect(testToolSpy).toHaveBeenCalledWith(
      'tool-test-1',
      expect.objectContaining({
        input: { city: 'Beijing' },
        savedRevision: 2,
      }),
    );

    await waitFor(() => {
      expect(screen.getByText(/HTTP 200/)).toBeTruthy();
      expect(screen.getByText(/145ms/)).toBeTruthy();
      expect(screen.getByText(/\{"temperature": 22\}/)).toBeTruthy();
    });
  });

  it('shows error when parameter JSON is invalid without calling testTool', async () => {
    const testToolSpy = vi.spyOn(toolApi, 'testTool');
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    const textarea = screen.getByLabelText(/Input Parameters/i);
    fireEvent.change(textarea, { target: { value: '{ invalid_json ' } });

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(testToolSpy).not.toHaveBeenCalled();
    });
  });

  it('paginates large output when result exceeds page size', async () => {
    const largeResult = 'A'.repeat(2500);
    vi.spyOn(toolApi, 'testTool').mockResolvedValue({
      httpStatus: 200,
      elapsedMs: 80,
      result: largeResult,
      truncated: false,
    });
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(screen.getByText(/Page 1 \/ 2/)).toBeTruthy();
    });

    const nextBtn = screen.getByRole('button', { name: /Next/i });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText(/Page 2 \/ 2/)).toBeTruthy();
    });

    const prevBtn = screen.getByRole('button', { name: /Previous/i });
    fireEvent.click(prevBtn);

    await waitFor(() => {
      expect(screen.getByText(/Page 1 \/ 2/)).toBeTruthy();
    });
  });

  it('handles 409 confirmation_required and re-runs with confirmation', async () => {
    const testToolSpy = vi
      .spyOn(toolApi, 'testTool')
      .mockRejectedValueOnce(
        new ApiError(409, JSON.stringify({
          code: 'confirmation_required',
          confirmationId: 'conf-123',
        })),
      )
      .mockResolvedValueOnce({
        httpStatus: 200,
        elapsedMs: 90,
        result: 'Confirmed execution succeeded',
        truncated: false,
      });
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(screen.getByText(/explicit approval/i)).toBeTruthy();
    });

    const confirmBtn = screen.getByRole('button', { name: /Confirm & Run/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(testToolSpy).toHaveBeenCalledTimes(2);
    });

    expect(testToolSpy).toHaveBeenLastCalledWith(
      'tool-test-1',
      expect.objectContaining({
        confirmed: true,
        confirmationId: 'conf-123',
      }),
    );

    await waitFor(() => {
      expect(screen.getByText(/Confirmed execution succeeded/)).toBeTruthy();
    });
  });

  it('stops running test on stop button click', async () => {
    let resolveTest!: (val: toolApi.ToolTestResult) => void;
    const testPromise = new Promise<toolApi.ToolTestResult>((resolve) => {
      resolveTest = resolve;
    });
    vi.spyOn(toolApi, 'testTool').mockReturnValue(testPromise);
    const stopToolTestSpy = vi.spyOn(toolApi, 'stopToolTest').mockResolvedValue({
      runId: 'run-test',
      status: 'stopped',
    });
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Stop/i })).toBeTruthy();
    });

    const stopBtn = screen.getByRole('button', { name: /Stop/i });
    fireEvent.click(stopBtn);

    await waitFor(() => {
      expect(stopToolTestSpy).toHaveBeenCalledTimes(1);
    });

    resolveTest({ httpStatus: 200, elapsedMs: 50, result: 'done', truncated: false });
  });

  it('stops running test on close button click', async () => {
    let resolveTest!: (val: toolApi.ToolTestResult) => void;
    const testPromise = new Promise<toolApi.ToolTestResult>((resolve) => {
      resolveTest = resolve;
    });
    vi.spyOn(toolApi, 'testTool').mockReturnValue(testPromise);
    const stopToolTestSpy = vi.spyOn(toolApi, 'stopToolTest').mockResolvedValue({
      runId: 'run-test',
      status: 'stopped',
    });
    const onClose = vi.fn();

    renderWithProviders(<ToolTestPanel tool={mockTool} open={true} onClose={onClose} />);

    const runBtn = screen.getByRole('button', { name: /Run Test/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Stop/i })).toBeTruthy();
    });

    const closeBtn = screen.getByRole('button', { name: /Close/i });
    fireEvent.click(closeBtn);

    expect(stopToolTestSpy).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);

    resolveTest({ httpStatus: 200, elapsedMs: 50, result: 'done', truncated: false });
  });
});
