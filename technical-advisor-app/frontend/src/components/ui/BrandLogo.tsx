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

import { useId } from 'react';
import { cn } from '@/lib/utils';

/**
 * DeepScope 品牌矢量 Logo（DS Monogram Prism，沿用 AgentScope 原有青蓝-电光蓝-靛紫渐变色系）。
 */
export function BrandLogo({
  className,
  iconClassName,
}: {
  className?: string;
  iconClassName?: string;
}) {
  const uid = useId().replace(/:/g, '');
  const cyanId = `ds-cyan-${uid}`;
  const slashId = `ds-slash-${uid}`;
  const indigoId = `ds-indigo-${uid}`;

  return (
    <div
      aria-hidden="true"
      className={cn(
        'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white ring-1 ring-slate-200/80 select-none',
        className,
      )}
    >
      <svg
        viewBox="0 0 100 100"
        fill="none"
        className={cn('h-5 w-5', iconClassName)}
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id={cyanId} x1="12" y1="16" x2="62" y2="84" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="#01C8FF" />
            <stop offset="55%" stopColor="#019DFB" />
            <stop offset="100%" stopColor="#0064FC" />
          </linearGradient>
          <linearGradient id={slashId} x1="22" y1="78" x2="86" y2="18" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="#0064FC" />
            <stop offset="55%" stopColor="#395EEF" />
            <stop offset="100%" stopColor="#4701EF" />
          </linearGradient>
          <linearGradient id={indigoId} x1="56" y1="18" x2="84" y2="84" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="#395EEF" />
            <stop offset="100%" stopColor="#4701EF" />
          </linearGradient>
        </defs>

        {/* S 左下至右上穿插斜杠与顶部横梁 */}
        <path
          d="M21 77 L50 30 C54.5 22.8 61.5 19 70 19 H85 L71 42"
          stroke={`url(#${slashId})`}
          strokeWidth="10.5"
          strokeLinecap="butt"
          strokeLinejoin="round"
        />

        {/* S 右侧棱镜折角与底部弧线 */}
        <path
          d="M85 19 L69.5 44.5 C67.8 47.3 68.5 50.5 71.2 52.8 C78.5 59 82 64.5 82 70.5 C82 78.5 75.5 84 66 84 H48"
          stroke={`url(#${indigoId})`}
          strokeWidth="10.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />

        {/* D 左侧主干与上下穿插圆弧 */}
        <path
          d="M46.5 23.5 C41.2 20.5 34.8 19 27 19 H16 V84 H29 C47.5 84 60 71 60 51.5 C60 44.5 58 38.2 54.5 33"
          stroke={`url(#${cyanId})`}
          strokeWidth="10.5"
          strokeLinecap="round"
          strokeLinejoin="miter"
        />
      </svg>
    </div>
  );
}
