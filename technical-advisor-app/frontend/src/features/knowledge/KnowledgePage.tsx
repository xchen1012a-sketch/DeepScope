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

import React from 'react';
import { useParams } from 'react-router-dom';
import { KnowledgeListPage } from './components/KnowledgeListPage';
import { LibraryDetailPage } from './components/LibraryDetailPage';

// 保持既有类型与 API 工具函数导出，确保外部（如 KnowledgeSelection.tsx）无感兼容
export {
  type LibrarySummary,
  type LibraryDocument,
  type LibraryDetail,
  type SearchHit,
  libraryKeys,
  listLibraries,
} from './types';

/**
 * 知识库主入口组件：
 * - 当路径包含 :libraryId 时渲染专职知识库工作台（文档管理 / 命中测试 / 检索配置）
 * - 否则渲染标准化 DataTable 列表视图（支持搜索、分页、快速测试与上传入口）
 */
export default function KnowledgePage() {
  const { libraryId } = useParams();
  if (libraryId) {
    return <LibraryDetailPage libraryId={libraryId} />;
  }
  return <KnowledgeListPage />;
}
