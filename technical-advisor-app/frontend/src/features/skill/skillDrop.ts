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

/** First file from a drop or picker. */
export function firstFile(files: FileList | File[] | null | undefined): File | null {
  const file = files?.[0];
  return file ?? null;
}

/** A pasted file, or pasted text saved as a markdown skill. */
export function fileFromClipboard(data: DataTransfer | null): File | null {
  if (data == null) {
    return null;
  }
  const dropped = firstFile(data.files);
  if (dropped != null) {
    return dropped;
  }
  const text = data.getData('text/plain');
  if (!text.trim()) {
    return null;
  }
  return new File([text], 'skill.md', { type: 'text/markdown' });
}
