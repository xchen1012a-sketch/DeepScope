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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { Markdown } from './Markdown';
afterEach(cleanup);

it('removes internal citation badges while retaining prose, code and real links', () => {
  const { container } = render(<Markdown text={'结论 [F26]，补充 [E3]。\n\n`[F26]` 与 E500 是原文。\n\n```txt\n[F26]\nE3\n```\n\n[来源 F26](https://example.com/F26)\n\n[F26](#evidence-F26)'} />);
  expect(screen.queryByRole('button', { name: /F26|E3/ })).toBeNull();
  expect(container.querySelector('p')?.textContent).toBe('结论 ，补充 。');
  expect(container.querySelector('code')?.textContent).toBe('[F26]');
  expect(container.querySelector('pre')?.textContent).toContain('[F26]\nE3');
  expect(screen.getByRole('link', { name: '来源 F26' }).getAttribute('href')).toBe('https://example.com/F26');
  expect(container.textContent).toContain('E500');
});

it('hides bare prefixes only for known fact ids and never removes legitimate technical tokens', () => {
  const { container } = render(<Markdown internalIds={['F26', 'E3']} text={'F26：检查完成\n\nE3 - 依赖问题\n\nF27: 业务编号\n\n`F26: const value`\n\n错误码 E500，型号 F26。'} />);
  expect(container.textContent).toContain('检查完成');
  expect(container.textContent).not.toContain('F26：');
  expect(container.textContent).not.toContain('E3 -');
  expect(container.textContent).toContain('F27: 业务编号');
  expect(container.querySelector('code')?.textContent).toBe('F26: const value');
  expect(container.textContent).toContain('错误码 E500，型号 F26。');
});

it('preserves bracketed technical path segments and embedded device identifiers in ordinary prose', () => {
  const { container } = render(<Markdown text={'文件位置 /tmp/[F26]/report.txt，设备标签 ABC[E3]-rev2。\n\n核验完成 [F26]，补充[E3]。'} />);
  expect(container.textContent).toContain('/tmp/[F26]/report.txt');
  expect(container.textContent).toContain('ABC[E3]-rev2');
  expect(container.textContent).not.toContain('完成 [F26]');
  expect(container.textContent).not.toContain('补充[E3]');
});
