/** Mirror the list API contract while retaining unpaged selector responses. */
export function listPageFixture<T extends object>(
  url: string | URL,
  rows: T[],
  options: { searchText?: (row: T) => string; filters?: (keyof T & string)[]; defaultModel?: T | null } = {},
) {
  const params = new URL(url).searchParams;
  if (!params.has('page')) return rows;
  const page = Number(params.get('page') || 1);
  const size = Number(params.get('size') || 10);
  const q = (params.get('q') || '').toLowerCase();
  const filtered = rows.filter(row => {
    const text = options.searchText ? options.searchText(row) : JSON.stringify(row);
    return text.toLowerCase().includes(q)
      && (options.filters ?? []).every(key => !params.get(key) || String(row[key]) === params.get(key));
  });
  return {
    items: filtered.slice((page - 1) * size, page * size),
    page, size, total: filtered.length, totalPages: Math.max(1, Math.ceil(filtered.length / size)),
    ...('defaultModel' in options ? { defaultModel: options.defaultModel } : {}),
  };
}
