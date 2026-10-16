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
import type { SlashCommand } from '@/api/advisor';
import { SlashCommandMenu } from './SlashCommandMenu';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

afterEach(() => {
  cleanup();
});

const mockCommands: SlashCommand[] = [
  {
    commandId: 'cmd-compact',
    code: 'compact',
    name: 'Compact context',
    description: 'Compact active session history',
    origin: 'builtin',
    targetType: 'action',
    targetId: 'compact',
    paramsJson: '{}',
    sortOrder: 1,
  },
  {
    commandId: 'cmd-skill-doc',
    code: 'doc',
    name: 'Generate Docs',
    description: 'Skill to generate docs',
    origin: 'custom',
    targetType: 'skill',
    targetId: 'doc-gen',
    paramsJson: '{}',
    sortOrder: 2,
  },
  {
    commandId: 'cmd-mcp-db',
    code: 'db',
    name: 'Database Query',
    description: 'MCP tool to inspect DB',
    origin: 'custom',
    targetType: 'mcp',
    targetId: 'db-tool',
    paramsJson: '{}',
    sortOrder: 3,
  },
  {
    commandId: 'cmd-prompt-refactor',
    code: 'refactor',
    name: 'Refactor Code',
    description: 'Prompt template for refactoring',
    origin: 'custom',
    targetType: 'prompt',
    targetId: 'refactor-prompt',
    paramsJson: '{}',
    sortOrder: 4,
  },
];

describe('SlashCommandMenu', () => {
  it('renders all commands with correct codes, names, and target types', () => {
    render(
      <I18nextProvider i18n={i18n}>
        <SlashCommandMenu
          commands={mockCommands}
          highlightIndex={0}
          onSelect={vi.fn()}
          onHighlight={vi.fn()}
        />
      </I18nextProvider>,
    );

    expect(screen.getByText('/compact')).toBeTruthy();
    expect(screen.getByText('Compact context')).toBeTruthy();
    expect(screen.queryByText('/doc')).toBeNull();
    expect(screen.queryByText('/db')).toBeNull();
    expect(screen.getByText('Generate Docs')).toBeTruthy();
    expect(screen.getByText('Database Query')).toBeTruthy();
    expect(screen.getByText('/refactor')).toBeTruthy();
  });

  it('triggers onSelect when a command is clicked', () => {
    const onSelect = vi.fn();
    render(
      <I18nextProvider i18n={i18n}>
        <SlashCommandMenu
          commands={mockCommands}
          highlightIndex={0}
          onSelect={onSelect}
          onHighlight={vi.fn()}
        />
      </I18nextProvider>,
    );

    fireEvent.click(screen.getByTestId('slash-command-doc'));
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(mockCommands[1]);
  });

  it('triggers onHighlight on mouse enter', () => {
    const onHighlight = vi.fn();
    render(
      <I18nextProvider i18n={i18n}>
        <SlashCommandMenu
          commands={mockCommands}
          highlightIndex={0}
          onSelect={vi.fn()}
          onHighlight={onHighlight}
        />
      </I18nextProvider>,
    );

    fireEvent.mouseEnter(screen.getByTestId('slash-command-db'));
    expect(onHighlight).toHaveBeenCalledWith(2);
  });

  it('renders empty message when command list is empty', () => {
    render(
      <I18nextProvider i18n={i18n}>
        <SlashCommandMenu
          commands={[]}
          highlightIndex={0}
          onSelect={vi.fn()}
          onHighlight={vi.fn()}
        />
      </I18nextProvider>,
    );

    expect(screen.getByText(i18n.t('chat.composer.slashMenu.noMatch'))).toBeTruthy();
  });
});
