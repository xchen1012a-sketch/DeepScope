/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
type Schema = Record<string, unknown>;
function object(value: unknown): Schema {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Schema : {};
}
function example(schema: Schema): unknown {
  if ('default' in schema) return schema.default;
  if ('example' in schema) return schema.example;
  if (Array.isArray(schema.examples) && schema.examples.length) return schema.examples[0];
  if ('const' in schema) return schema.const;
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[0];
  switch (schema.type) {
    case 'number': case 'integer': return typeof schema.minimum === 'number' ? schema.minimum : 0;
    case 'boolean': return false;
    case 'array': return [];
    case 'object': return template(schema);
    default: return '';
  }
}
function template(schema: Schema): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  const required = Array.isArray(schema.required) ? schema.required : [];
  for (const [name, raw] of Object.entries(object(schema.properties))) {
    const field = object(raw);
    if (required.includes(name) || ['default', 'example', 'examples', 'const', 'enum'].some(key => key in field)) result[name] = example(field);
  }
  return result;
}
/** A typed editable template, not an AI result or a promise of business-valid identifiers. */
export function toolTestTemplate(schema: string): string {
  try { return JSON.stringify(template(object(JSON.parse(schema))), null, 2); }
  catch { return '{}'; }
}
