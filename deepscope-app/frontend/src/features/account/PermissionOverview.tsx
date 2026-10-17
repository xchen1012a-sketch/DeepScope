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

import { useTranslation } from 'react-i18next';
import { PermissionTree, type PermissionNode } from '@/components/PermissionTree';

export function PermissionOverview({ permissions }: { permissions: string[] }) {
  const { t } = useTranslation();
  const groups = new Map<string, string[]>();
  for (const permission of [...new Set(permissions)].sort()) {
    const domain = permission.split('.')[0];
    groups.set(domain, [...(groups.get(domain) ?? []), permission]);
  }
  if (!groups.size) return <p className="text-sm text-muted-foreground">{t('account.noPermissions')}</p>;
  const nodes: PermissionNode[] = [...groups].map(([domain, entries]) => {
    const groupKey = ['account', 'permissionGroups', domain].join('.');
    const name = t(groupKey, { defaultValue: domain });
    return { id: domain, name, nameEn: name, children: entries.map(permission => {
      const key = ['account', 'permissions', permission].join('.');
      const label = t(key, { defaultValue: permission });
      return { id: permission, name: label, nameEn: label, children: [] };
    }) };
  });
  return <PermissionTree nodes={nodes} value={permissions} readOnly />;
}
