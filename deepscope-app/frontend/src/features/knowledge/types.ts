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

import { listQuery, selectionQuery, type SelectionContext } from '@/api/listQuery';
import { api } from '@/lib/apiClient';

export interface LibrarySummary {
  libraryId: string;
  name: string;
  description: string;
  chunkSize: number;
  overlap: number;
  recallLimit: number;
  scoreThreshold: number;
  readyCount: number;
  canEdit: boolean;
  embeddingEndpointId?: string | null;
  embeddingModelName?: string | null;
  documentCount?: number;
  rebuilding?: boolean;
}

export interface LibraryDocument {
  documentId: string;
  filename: string;
  sizeBytes: number;
  status: 'parsing' | 'ready' | 'partial' | 'failed';
  failure: string;
  createdAt: number;
  pageCount?: number;
  processedPages?: number;
  failedPages?: number;
  stage?: 'queued' | 'parsing' | 'embedding' | 'publishing' | '';
  embeddedChunks?: number;
  totalChunks?: number;
}

export interface LibraryDetail extends LibrarySummary {
  documents: LibraryDocument[];
  parsingCount?: number;
}

export interface SearchHit {
  library: string;
  document: string;
  passage: string;
  score: number;
  libraryId?: string;
  documentId?: string;
  source?: PdfSource | null;
  context?: { text: string; source: PdfSource }[];
  vectorScore?: number | null;
  rankingMethod?: string;
  warnings?: string[];
}

export interface PdfBox { x: number; y: number; width: number; height: number }
export interface PdfSource {
  spans?: { blockId: string; type: string; box?: PdfBox | null }[];
  pageNumber: number;
  pageLabel?: string | null;
  blockId: string;
  type: string;
  box?: PdfBox | null;
  method: string;
  interpretation: boolean;
  warnings: string[];
}
export interface PdfPage {
  pageNumber: number;
  pageLabel?: string | null;
  width: number;
  height: number;
  status: 'ready' | 'blank' | 'review' | 'failed';
  method: 'native' | 'vision';
  nativeText: string;
  blocks: { id: string; type: string; text: string; box?: PdfBox | null; interpretation: boolean }[];
  warnings: string[];
}

export const libraryKeys = { libraries: ['advisor', 'knowledge'] as const };

export function listLibraries(query = '', selection?: SelectionContext): Promise<LibrarySummary[]> {
  return api.get<LibrarySummary[]>(selection ? selectionQuery('/api/advisor/knowledge', selection) : listQuery('/api/advisor/knowledge', query));
}
