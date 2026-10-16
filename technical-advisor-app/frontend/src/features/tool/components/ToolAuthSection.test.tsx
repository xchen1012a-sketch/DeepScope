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
import { ToolAuthSection } from './ToolAuthSection';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

function renderWithI18n(ui: React.ReactElement) {
  return render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);
}

afterEach(() => {
  cleanup();
});

describe('ToolAuthSection', () => {
  it('renders default none in create mode without credential inputs', () => {
    const onChange = vi.fn();
    renderWithI18n(<ToolAuthSection mode="create" onChange={onChange} />);

    expect(screen.getByText('Authentication')).toBeTruthy();
    expect(screen.queryByLabelText('Credential Action')).toBeNull();
    const typeSelect = screen.getByLabelText('Auth Mode') as HTMLSelectElement;
    expect(typeSelect.value).toBe('none');
    expect(screen.queryByLabelText('Key Value')).toBeNull();
    expect(screen.queryByLabelText('Token')).toBeNull();
  });

  it('switches to apiKey and renders api key fields in create mode', () => {
    const onChange = vi.fn();
    renderWithI18n(
      <ToolAuthSection
        mode="create"
        value={{ type: 'apiKey', name: 'X-My-Key', in: 'header', value: 'secret-123' }}
        onChange={onChange}
      />,
    );

    const typeSelect = screen.getByLabelText('Auth Mode') as HTMLSelectElement;
    expect(typeSelect.value).toBe('apiKey');

    const placementSelect = screen.getByLabelText('Placement') as HTMLSelectElement;
    expect(placementSelect.value).toBe('header');

    const nameInput = screen.getByLabelText('Key Name') as HTMLInputElement;
    expect(nameInput.value).toBe('X-My-Key');

    const valInput = screen.getByLabelText('Key Value') as HTMLInputElement;
    expect(valInput.value).toBe('secret-123');

    fireEvent.change(valInput, { target: { value: 'updated-key' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'apiKey',
        value: 'updated-key',
      }),
    );
  });

  it('switches to bearer and renders token input', () => {
    const onChange = vi.fn();
    renderWithI18n(
      <ToolAuthSection
        mode="create"
        value={{ type: 'bearer', value: 'my-bearer-token' }}
        onChange={onChange}
      />,
    );

    const tokenInput = screen.getByLabelText('Token') as HTMLInputElement;
    expect(tokenInput.value).toBe('my-bearer-token');
    expect(screen.queryByLabelText('Key Name')).toBeNull();

    fireEvent.change(tokenInput, { target: { value: 'new-token' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'bearer',
        value: 'new-token',
      }),
    );
  });

  it('switches to basic and renders username/password inputs', () => {
    const onChange = vi.fn();
    renderWithI18n(
      <ToolAuthSection
        mode="create"
        value={{ type: 'basic', username: 'admin', password: 'secretpassword' }}
        onChange={onChange}
      />,
    );

    const userInput = screen.getByLabelText('Username') as HTMLInputElement;
    expect(userInput.value).toBe('admin');

    const passInput = screen.getByLabelText('Password') as HTMLInputElement;
    expect(passInput.value).toBe('secretpassword');

    fireEvent.change(passInput, { target: { value: 'newpassword' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'basic',
        password: 'newpassword',
      }),
    );
  });

  it('renders edit mode with keep/replace/clear action controls', () => {
    const onChange = vi.fn();
    renderWithI18n(
      <ToolAuthSection
        mode="edit"
        hasExistingCredential={true}
        value={{ action: 'keep' }}
        onChange={onChange}
      />,
    );

    expect(
      screen.getByText(
        "This tool has configured credentials. Choose 'Keep existing credential' to leave them unchanged.",
      ),
    ).toBeTruthy();
    const actionSelect = screen.getByLabelText('Credential Action') as HTMLSelectElement;
    expect(actionSelect.value).toBe('keep');

    // Inputs should not be shown when action is keep
    expect(screen.queryByLabelText('Key Value')).toBeNull();
    expect(screen.queryByLabelText('Token')).toBeNull();

    // Change action to clear
    fireEvent.change(actionSelect, { target: { value: 'clear' } });
    expect(onChange).toHaveBeenCalledWith({ action: 'clear' });

    // Change action to replace
    fireEvent.change(actionSelect, { target: { value: 'replace' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        action: 'replace',
        type: 'apiKey',
      }),
    );
  });

  it('renders credential inputs when action is replace in edit mode', () => {
    const onChange = vi.fn();
    renderWithI18n(
      <ToolAuthSection
        mode="edit"
        value={{ action: 'replace', type: 'bearer', value: 'old-token' }}
        onChange={onChange}
      />,
    );

    const actionSelect = screen.getByLabelText('Credential Action') as HTMLSelectElement;
    expect(actionSelect.value).toBe('replace');

    const tokenInput = screen.getByLabelText('Token') as HTMLInputElement;
    expect(tokenInput.value).toBe('old-token');

    fireEvent.change(tokenInput, { target: { value: 'new-replaced-token' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        action: 'replace',
        type: 'bearer',
        value: 'new-replaced-token',
      }),
    );
  });
});
