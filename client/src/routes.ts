/** Build an app destination without carrying the previous screen's job or mode. */
export function appLink(params: Record<string, string | null> = {}, hash = ''): string {
  const current = new URL(location.href)
  const url = new URL(location.pathname, location.href)
  for (const name of ['key', 'lang', ...(import.meta.env.DEV ? ['api'] : [])]) {
    const value = current.searchParams.get(name)
    if (value) url.searchParams.set(name, value)
  }
  for (const [name, value] of Object.entries(params)) {
    if (value === null) url.searchParams.delete(name)
    else url.searchParams.set(name, value)
  }
  url.hash = hash
  return url.toString()
}
