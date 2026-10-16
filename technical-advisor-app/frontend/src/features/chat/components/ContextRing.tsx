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

import { cn } from '@/lib/utils';

/** 18px 圆环、2px 环宽对应的半径与周长；弧长随剩余比例填充。 */
const RING_RADIUS = 8;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

type RingTone = 'healthy' | 'warning' | 'critical';

/** 三档健康状态：高于 30% 充裕、15%～30% 预警、低于 15% 告急。 */
function ringTone(remainingPercent: number): RingTone {
  if (remainingPercent < 15) return 'critical';
  if (remainingPercent <= 30) return 'warning';
  return 'healthy';
}

const ARC_CLASS: Record<RingTone, string> = {
  healthy: 'stroke-indigo-500',
  warning: 'stroke-amber-500',
  critical: 'stroke-rose-500',
};

/**
 * Claude Coder 模式上下文圆环：
 * 纯圆环指示器（环内无数字），弧长与色彩代表上下文已用容量。
 */
export function ContextRing({
  remainingPercent,
  capacityKnown,
}: {
  remainingPercent: number;
  capacityKnown: boolean;
}) {
  const clampedRemaining = Math.max(0, Math.min(100, remainingPercent));
  const usedPercent = Math.max(0, Math.min(100, 100 - clampedRemaining));
  const dashOffset = capacityKnown
    ? RING_CIRCUMFERENCE * (1 - usedPercent / 100)
    : RING_CIRCUMFERENCE;
  const tone = ringTone(clampedRemaining);
  return (
    <span className="relative inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center" data-testid="context-ring">
      <svg width={18} height={18} viewBox="0 0 18 18" className="-rotate-90" aria-hidden="true">
        <circle cx={9} cy={9} r={RING_RADIUS} fill="none" strokeWidth={2} className="stroke-slate-200" />
        <circle
          cx={9}
          cy={9}
          r={RING_RADIUS}
          fill="none"
          strokeWidth={2}
          strokeLinecap="round"
          strokeDasharray={RING_CIRCUMFERENCE}
          strokeDashoffset={dashOffset}
          className={cn(
            'transition-[stroke-dashoffset,stroke] duration-500 ease-out',
            capacityKnown ? ARC_CLASS[tone] : 'stroke-slate-300',
          )}
        />
      </svg>
      <span className="sr-only">{capacityKnown ? `${clampedRemaining}%` : '—'}</span>
    </span>
  );
}
