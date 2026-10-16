// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import type { SubagentRecord } from '../useAdvisorConversation';
import { SubagentView } from './SubagentView';
import { SubagentCard } from './SubagentCard';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
afterEach(cleanup);

const record = (overrides: Partial<SubagentRecord> = {}): SubagentRecord => ({
  id: 'internal-task', name: 'Investigator', task: 'Inspect the project', status: 'running',
  factCount: 0, facts: [], summary: '', steps: [], ...overrides,
});

function renderView(value: SubagentRecord) {
  return render(<SubagentView record={value} onBack={vi.fn()} />, {
    wrapper: ({ children }) => <I18nextProvider i18n={i18n}>{children}</I18nextProvider>,
  });
}

it('shows facts, sources and conclusions without internal identifiers', () => {
  renderView(record({ facts: [{ id: 'F26', fact: 'The build uses Maven', source: 'pom.xml', conclusion: 'Confirmed' }] }));
  expect(screen.queryByText('F26')).toBeNull();
  expect(screen.getByText('The build uses Maven')).toBeTruthy();
  expect(screen.getByText(/pom.xml/)).toBeTruthy();
  expect(screen.getByText(/Confirmed/)).toBeTruthy();
  expect(screen.queryByText('internal-task')).toBeNull();
});

it('removes known fact labels from prose while preserving model names and source paths', () => {
  renderView(record({ status: 'done', facts: [{ id: 'F26', fact: 'F26: The E500 model works [E3]', source: 'F26.md', conclusion: 'Confirmed [F26]' }], summary: 'F26: Verified on E500' }));
  expect(screen.getByText('The E500 model works')).toBeTruthy();
  expect(screen.getByText('Verified on E500')).toBeTruthy();
  expect(screen.getByText(/F26.md/)).toBeTruthy();
  expect(screen.queryByText('F26')).toBeNull();
  expect(screen.queryByText('E3')).toBeNull();
});

it('shows the current operation on a running task card without replacing its goal', () => {
  render(<I18nextProvider i18n={i18n}><SubagentCard record={record({ steps: [{ subtaskId: 'internal-task', order: 1, kind: 'tool_call', tool: { id: 'tool-1', name: 'read_file', args: '{}', status: 'running', startedAt: null } }] })} onOpen={vi.fn()} /></I18nextProvider>);
  expect(screen.getByRole('button').textContent).toContain('Inspect the project');
  expect(screen.getByRole('button').textContent).toContain('Reading file');
});

it('streams only the current thinking step and settles it when the task ends', () => {
  const value = record({ steps: [
    { subtaskId: 'internal-task', order: 1, kind: 'thinking', text: 'Earlier analysis' },
    { subtaskId: 'internal-task', order: 2, kind: 'tool_call', tool: { id: 'tool-1', name: 'read_file', args: '{}', status: 'done', startedAt: null } },
    { subtaskId: 'internal-task', order: 3, kind: 'thinking', text: 'Current analysis' },
  ] });
  const view = renderView(value);
  expect(screen.getByText('Analyzing')).toBeTruthy();
  expect(view.container.querySelectorAll('[aria-busy="true"]')).toHaveLength(1);
  expect(screen.getByText('Current analysis')).toBeTruthy();
  view.rerender(<SubagentView record={{ ...value, status: 'done', summary: 'Completed' }} onBack={vi.fn()} />);
  expect(screen.queryByText('Analyzing')).toBeNull();
  expect(view.container.querySelector('[aria-busy="true"]')).toBeNull();
  expect(screen.getByText('Completed')).toBeTruthy();
});
