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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import * as agentsApi from '@/api/agent';
import * as draftsApi from '@/api/agentCreation';
import * as advisorApi from '@/api/advisor';
import * as toolApi from '@/api/tool';
import { api, apiFetch } from '@/lib/apiClient';
import AgentPage from './AgentPage';

vi.mock('@/api/agent', async importOriginal => ({ ...await importOriginal<typeof import('@/api/agent')>(), listAgents: vi.fn(), getAgent: vi.fn(), updateAgent: vi.fn(), readAgentFile: vi.fn(), saveAgentFile: vi.fn(), deleteAgent: vi.fn() }));
vi.mock('@/api/agentCreation', async importOriginal => ({ ...await importOriginal<typeof import('@/api/agentCreation')>(), listDrafts: vi.fn(), createDraft: vi.fn(), checkDraft: vi.fn(), activateDraft: vi.fn(), getDraft: vi.fn(), updateDraft: vi.fn(), deleteDraft: vi.fn() }));
vi.mock('@/api/advisor', async importOriginal => ({ ...await importOriginal<typeof import('@/api/advisor')>(), createSession: vi.fn() }));

vi.mock('@/api/tool', async importOriginal => ({ ...await importOriginal<typeof import('@/api/tool')>(), listTools: vi.fn(), listMcpServers: vi.fn(), getToolSelection: vi.fn(), saveToolSelection: vi.fn() }));
vi.mock('@/lib/apiClient', async original => ({ ...await original<typeof import('@/lib/apiClient')>(), api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn() }));

const summaries: agentsApi.AgentSummary[] = [
  { agentId: 'a', name: 'Alpha', description: 'Review code', origin: 'personal', mine: true, canEdit: true, counts: { skills: 0, subagents: 0 } },
  { agentId: 'b', name: 'Beta', description: 'Summarize meetings', origin: 'personal', mine: false, canEdit: false, counts: { skills: 0, subagents: 0 } },
];
const detail = (id: string): agentsApi.AgentDetail => ({ ...summaries.find(agent => agent.agentId === id)!, sections: { skills: [], tools: [], subagents: [], knowledge: [] } });
const toolCatalog: toolApi.CatalogTool[] = [{ toolId: 't1', name: 'Lookup ticket', description: '', inputSchema: '{}', origin: 'http', permission: 'allow', ownerUserId: 'u', mcpServerId: null, httpMethod: 'GET', httpUrl: 'https://example.test', approved: true, canEdit: true }];
function setup(path = '/agent', useDataRouter = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const i18n = createInstance();
  void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
  const routeElements = ['/agent', '/agent/:agentId', '/agent/creation/:draftId', '/agent/new'].map(path => ({ path, element: <AgentPage /> }));
  const router = useDataRouter ? createMemoryRouter([...routeElements, { path: '/chat/:sessionId', element: <p>Conversation opened</p> }], { initialEntries: [path] }) : null;
  const view = render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}>{router ? <RouterProvider router={router} /> : <MemoryRouter initialEntries={[path]}><Routes>
    {routeElements.map(route => <Route key={route.path} {...route} />)}
    <Route path="/chat/:sessionId" element={<p>Conversation opened</p>} />
  </Routes></MemoryRouter>}</QueryClientProvider></I18nextProvider>);
  return { ...view, router };

}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(apiFetch).mockImplementation(async path => {
    if (path === '/api/advisor/models/active') return { modelName: 'test-model' };
    throw new Error(`Unexpected API request: ${path}`);
  });
  vi.mocked(agentsApi.listAgents).mockResolvedValue(summaries);
  vi.mocked(draftsApi.listDrafts).mockResolvedValue([]);
  vi.mocked(api.get).mockImplementation(async path => {
    const url = new URL(path, 'https://test.local');
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const page = Number(url.searchParams.get('page') ?? 1);
    const size = Number(url.searchParams.get('size') ?? 10);
    const paged = <T,>(rows: T[]) => ({ items: rows.slice((page - 1) * size, page * size), page, size, total: rows.length, totalPages: Math.max(1, Math.ceil(rows.length / size)) });
    // 选择器页签走分页列表接口；详情页挂载时四个列表都会发出请求。
    if (url.pathname === '/api/advisor/tools') return paged(toolCatalog.filter(tool => `${tool.name} ${tool.description}`.toLowerCase().includes(q)));
    if (url.pathname === '/api/advisor/mcp-servers') return paged([]);
    if (url.pathname === '/api/advisor/skills') return paged([]);
    if (url.pathname === '/api/advisor/knowledge') return paged([]);
    if (url.pathname !== '/api/advisor/agent-overview') throw new Error(`Unexpected list request: ${path}`);
    const agents = await agentsApi.listAgents();
    const drafts = await draftsApi.listDrafts();
    const rows = [
      ...agents.map(agent => ({ id: agent.agentId, name: agent.name, description: agent.description, draft: false, enabled: agent.enabled !== false, canEdit: agent.canEdit, canDelete: agent.canEdit })),
      ...drafts.map(draft => ({ id: draft.draftId, name: draft.name || 'Untitled agent', description: draft.purpose || draft.description, draft: true })),
    ].filter(item => `${item.name} ${item.description}`.toLowerCase().includes(q)).sort((a, b) => a.name.localeCompare(b.name));
    return { items: rows.slice((page - 1) * size, page * size), page, size, total: rows.length, totalPages: Math.max(1, Math.ceil(rows.length / size)) };
  });
  vi.mocked(toolApi.getToolSelection).mockResolvedValue({ toolIds: [], serverIds: [] });
  vi.mocked(toolApi.saveToolSelection).mockResolvedValue(undefined);
  vi.mocked(agentsApi.getAgent).mockImplementation(async id => detail(id));
  vi.mocked(agentsApi.readAgentFile).mockImplementation(async id => ({ content: `${id} work instructions` }));
  vi.mocked(agentsApi.updateAgent).mockImplementation(async (id, input) => ({ ...detail(id), ...input }));
  vi.mocked(agentsApi.saveAgentFile).mockResolvedValue(undefined);
  vi.mocked(advisorApi.createSession).mockResolvedValue({ sessionId: 's1' } as Awaited<ReturnType<typeof advisorApi.createSession>>);
});
afterEach(cleanup);
async function select(name: string) { fireEvent.click(await screen.findByRole('button', { name: new RegExp(name) })); }

describe('agent management split view', () => {
  it('shows persisted status and toggles an agent through the backend', async () => {
    let enabled = true;
    vi.mocked(agentsApi.listAgents).mockImplementation(async () => [{ ...summaries[0], enabled }]);
    vi.mocked(agentsApi.updateAgent).mockImplementation(async (id, input) => {
      enabled = input.enabled ?? enabled;
      return { ...detail(id), enabled };
    });
    setup();
    const row = await screen.findByRole('row', { name: /Alpha/ });
    expect(within(row).getByText('Enabled')).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(agentsApi.updateAgent).toHaveBeenCalledWith('a', { enabled: false }));
    await screen.findByRole('button', { name: 'Enable' });
    expect(within(screen.getByRole('row', { name: /Alpha/ })).getByText('Disabled')).toBeTruthy();
  });

  it('confirms deletion from the standard list before sending a delete request', async () => {
    setup();
    const row = await screen.findByRole('row', { name: /Alpha/ });
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    expect(agentsApi.deleteAgent).not.toHaveBeenCalled();
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(agentsApi.deleteAgent).toHaveBeenCalledWith('a'));
  });
  it('requests the next overview page without fetching the complete catalogs', async () => {
    vi.mocked(agentsApi.listAgents).mockResolvedValue(Array.from({ length: 12 }, (_, index) => ({
      ...summaries[0], agentId: `agent-${index}`, name: `Agent ${String(index).padStart(2, '0')}`,
    })));
    setup();
    await screen.findByRole('row', { name: /Agent 00/ });
    expect(api.get).toHaveBeenCalledWith('/api/advisor/agent-overview?page=1&size=10');
    expect(screen.queryByRole('row', { name: /Agent 10/ })).toBeNull();
    fireEvent.click(screen.getAllByRole('button', { name: 'Next' }).slice(-1)[0]);
    await screen.findByRole('row', { name: /Agent 10/ });
    expect(api.get).toHaveBeenLastCalledWith('/api/advisor/agent-overview?page=2&size=10');
    expect(screen.queryByRole('row', { name: /Agent 00/ })).toBeNull();
  });

  it('submits the same agent keyword again and preserves the unnamed draft label', async () => {
    setup('/agent/a');
    const search = await screen.findByRole('textbox', { name: 'Search agents' });
    await screen.findByRole('button', { name: /Beta/ });
    const initial = vi.mocked(api.get).mock.calls.length;
    fireEvent.change(search, { target: { value: 'meetings' } });
    expect(api.get).toHaveBeenCalledTimes(initial);
    fireEvent.keyDown(search, { key: 'Enter' });
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(initial + 1));
    fireEvent.keyDown(search, { key: 'Enter' });
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(initial + 2));
    vi.mocked(api.get).mockResolvedValue({ items: [{ id: 'draft', name: '', description: 'purpose', draft: true }], page: 1, size: 10, total: 1, totalPages: 1 });
    fireEvent.change(search, { target: { value: '' } });
    fireEvent.keyDown(search, { key: 'Enter' });
    expect(await screen.findByRole('button', { name: /Untitled agent/ })).toBeTruthy();
  });

  it('shows the standard overview headers and text-only edit action on first entry', async () => {
    setup();
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('columnheader').map(header => header.textContent)).toEqual(['Name', 'Summary', 'Type', 'Status', 'Actions']);
    const row = await within(table).findByRole('row', { name: /Alpha/ });
    const edit = within(row).getByRole('button', { name: 'Edit' });
    expect(edit.querySelector('svg')).toBeNull();
    fireEvent.click(edit);
    expect(await screen.findByDisplayValue('Alpha')).toBeTruthy();
  });

  it('keeps the standard overview header while loading and when results are empty', async () => {
    vi.mocked(agentsApi.listAgents).mockResolvedValue([]);
    vi.mocked(draftsApi.listDrafts).mockResolvedValue([]);
    setup();
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId('data-table-loading')).toBeNull());
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeTruthy();
    expect(screen.getByTestId('pagination')).toBeTruthy();
  });

  it('selects an agent from the left list and renders editable details on the right without an edit navigation step', async () => {
    setup();
    await select('Alpha');
    expect(await screen.findByDisplayValue('Alpha')).toBeTruthy();
    expect(await screen.findByDisplayValue('a work instructions')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Start conversation' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Tools' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    await select('Beta');
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
    expect((screen.getByDisplayValue('Beta') as HTMLInputElement).disabled).toBe(true);
    expect((await screen.findByDisplayValue('b work instructions') as HTMLTextAreaElement).readOnly).toBe(true);
  });

  it('keeps deep-linked details in the same searchable list layout', async () => {
    setup('/agent/a');
    expect(await screen.findByDisplayValue('Alpha')).toBeTruthy();
    const search = await screen.findByRole('textbox', { name: 'Search agents' });
    fireEvent.change(search, { target: { value: 'meetings' } });
    fireEvent.keyDown(search, { key: 'Enter' });
    expect(await screen.findByRole('button', { name: /Beta/ })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: /Alpha/ })).toBeNull());
    expect(api.get).toHaveBeenLastCalledWith('/api/advisor/agent-overview?page=1&size=10&q=meetings');
    expect(screen.getByDisplayValue('Alpha')).toBeTruthy();
  });

  it('can stay or discard changes before selecting another agent', async () => {
    setup('/agent/a');
    fireEvent.change(await screen.findByDisplayValue('Alpha'), { target: { value: 'Unsaved name' } });
    await select('Beta');
    expect(await screen.findByRole('dialog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.getByDisplayValue('Unsaved name')).toBeTruthy();
    await select('Beta');
    fireEvent.click(screen.getByRole('button', { name: 'Discard and switch' }));
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
    expect(agentsApi.updateAgent).not.toHaveBeenCalled();
  });

  it('saves both basic information and work instructions before switching', async () => {
    setup('/agent/a');
    fireEvent.change(await screen.findByDisplayValue('Alpha'), { target: { value: 'Changed name' } });
    fireEvent.change(await screen.findByDisplayValue('a work instructions'), { target: { value: 'Changed instructions' } });
    await select('Beta');
    fireEvent.click(await screen.findByRole('button', { name: 'Save and switch' }));
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
    expect(agentsApi.updateAgent).toHaveBeenCalledWith('a', { name: 'Changed name', description: 'Review code' });
    expect(agentsApi.saveAgentFile).toHaveBeenCalledWith('a', 'AGENTS.md', 'Changed instructions');
  });

  it('retains the current agent and editor when saving before switching fails', async () => {
    vi.mocked(agentsApi.updateAgent).mockRejectedValue(new Error('Offline'));
    setup('/agent/a');
    fireEvent.change(await screen.findByDisplayValue('Alpha'), { target: { value: 'Keep this name' } });
    await select('Beta');
    fireEvent.click(await screen.findByRole('button', { name: 'Save and switch' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByDisplayValue('Keep this name')).toBeTruthy();
    expect(screen.queryByDisplayValue('Beta')).toBeNull();
  });

  it('shows a recoverable details error while keeping the left list usable', async () => {
    vi.mocked(agentsApi.getAgent).mockRejectedValueOnce(new Error('Offline'));
    setup('/agent/a');
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
    await select('Beta');
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
  });

  it('ignores a late response for a previously selected agent', async () => {
    let resolveAlpha!: (value: agentsApi.AgentDetail) => void;
    vi.mocked(agentsApi.getAgent).mockImplementation(id => id === 'a' ? new Promise(resolve => { resolveAlpha = resolve; }) : Promise.resolve(detail(id)));
    setup('/agent/a');
    await select('Beta');
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
    resolveAlpha(detail('a'));
    await waitFor(() => expect(screen.queryByDisplayValue('Alpha')).toBeNull());
  });

  it('opens a locked conversation through the existing session API', async () => {
    setup('/agent/a');
    fireEvent.click(await screen.findByRole('button', { name: 'Start conversation' }));
    expect(await screen.findByText('Conversation opened')).toBeTruthy();
    expect(advisorApi.createSession).toHaveBeenCalledWith('a');
  });
  it('protects unsaved tool selections when changing agents while allowing free tab switching', async () => {
    setup('/agent/a');
    fireEvent.click(await screen.findByRole('tab', { name: 'Tools' }));
    fireEvent.click(await screen.findByRole('checkbox', { name: /Lookup ticket/ }));
    fireEvent.click(screen.getByRole('tab', { name: 'Skills' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await select('Beta');
    expect(await screen.findByRole('dialog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    await select('Beta');
    fireEvent.click(await screen.findByRole('button', { name: 'Save and switch' }));
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
    expect(toolApi.saveToolSelection).toHaveBeenCalledWith('a', { toolIds: ['t1'], serverIds: [] });
  });

  it('resumes a selected draft inline and protects a pending draft edit', async () => {
    const draft: draftsApi.DraftDto = { draftId: 'd1', ownerUserId: 'u', mode: 'describe', sourceAgentId: null, purpose: 'Draft purpose', name: 'Draft helper', description: '', behavior: 'Draft behavior', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(draftsApi.listDrafts).mockResolvedValue([draft]);
    vi.mocked(draftsApi.getDraft).mockResolvedValue(draft);
    setup('/agent/creation/d1');
    const input = await screen.findByDisplayValue('Draft purpose');
    fireEvent.change(input, { target: { value: 'Unfinished edit' } });
    await select('Alpha');
    expect(await screen.findByRole('dialog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.getByDisplayValue('Unfinished edit')).toBeTruthy();
    await select('Alpha');
    fireEvent.click(screen.getByRole('button', { name: 'Discard and switch' }));
    expect(await screen.findByDisplayValue('Alpha')).toBeTruthy();
    expect(draftsApi.updateDraft).not.toHaveBeenCalled();
  });

  it('keeps the selection frozen while the requested save is in flight', async () => {
    let complete!: () => void;
    vi.mocked(toolApi.saveToolSelection).mockImplementation(() => new Promise<void>(resolve => { complete = resolve; }));
    setup('/agent/a');
    fireEvent.click(await screen.findByRole('tab', { name: 'Tools' }));
    const checkbox = await screen.findByRole('checkbox', { name: /Lookup ticket/ });
    fireEvent.click(checkbox);
    await select('Beta');
    fireEvent.click(await screen.findByRole('button', { name: 'Save and switch' }));
    await waitFor(() => expect((checkbox as HTMLInputElement).disabled).toBe(true));
    complete();
    expect(await screen.findByDisplayValue('Beta')).toBeTruthy();
  });

  it('supports keyboard focus through the top tabs without silently discarding edits', async () => {
    setup('/agent/a');
    const instructions = await screen.findByRole('tab', { name: 'Work instructions' });
    instructions.focus();
    fireEvent.keyDown(instructions, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'Tools' }));
    expect(instructions.getAttribute('aria-selected')).toBe('true');
  });

  it('synchronizes the newly saved draft URL without treating it as an unsaved navigation', async () => {
    const draft: draftsApi.DraftDto = { draftId: 'd2', ownerUserId: 'u', mode: 'describe', sourceAgentId: null, purpose: 'A new task', name: '', description: '', behavior: '', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(draftsApi.createDraft).mockResolvedValue(draft);
    vi.mocked(draftsApi.getDraft).mockResolvedValue(draft);
    const { container, router } = setup('/agent/new', true);
    await waitFor(() => expect(container.querySelector('textarea')).toBeTruthy());
    fireEvent.change(container.querySelector('textarea')!, { target: { value: 'A new task' } });
    expect(draftsApi.createDraft).not.toHaveBeenCalled();
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/active'));
    fireEvent.click(screen.getByRole('button', { name: i18nResources.en.app.agent.creation.generateAi }));
    await waitFor(() => expect(router!.state.location.pathname).toBe('/agent/creation/d2'), { timeout: 2500 });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('navigates to agent detail after a completed creation without creating conversation session', async () => {
    const draft: draftsApi.DraftDto = { draftId: 'd2', ownerUserId: 'u', mode: 'describe', sourceAgentId: null, purpose: 'A new task', name: 'New helper', description: '', behavior: 'Answer questions', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(draftsApi.getDraft).mockResolvedValue(draft);
    vi.mocked(draftsApi.checkDraft).mockResolvedValue({ issues: [] });
    vi.mocked(draftsApi.activateDraft).mockResolvedValue({ operationId: 'o', draftId: 'd2', operationType: 'activation', requestKey: 'k', baseRevision: 1, state: 'succeeded', stage: 'complete', preallocatedAgentId: 'a', startedAt: 0 });
    const { router } = setup('/agent/creation/d2', true);
    await screen.findByDisplayValue('A new task');
    const buttons = screen.getAllByRole('button', { name: i18nResources.en.app.agent.creation.createAndStart });
    fireEvent.click(buttons[0]);
    await waitFor(() => expect(router!.state.location.pathname).toBe('/agent/a'));
    expect(advisorApi.createSession).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('hides only the workspace tab while retaining the other management tabs', async () => {
    setup('/agent/a');
    await screen.findByRole('tab', { name: 'Work instructions' });
    expect(screen.queryByRole('tab', { name: 'Workspace' })).toBeNull();
    expect(screen.getAllByRole('tab')).toHaveLength(5);
    for (const label of ['Tools', 'Skills', 'Knowledge', 'Subagents']) {
      expect(screen.getByRole('tab', { name: label })).toBeTruthy();
    }
  });

});
