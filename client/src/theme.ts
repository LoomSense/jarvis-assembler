/** Shared appearance control, persisted across all non-immersive screens. */
export function themeButton(): HTMLButtonElement {
  const button = document.createElement('button')
  button.className = 'theme-toggle'
  button.type = 'button'
  const label = () => {
    const light = document.documentElement.dataset.theme === 'light'
    const en = document.documentElement.lang === 'en'
    button.setAttribute('aria-label', en ? `Switch to ${light ? 'dark' : 'light'} theme` : `${light ? 'Σκούρο' : 'Φωτεινό'} θέμα`)
    button.title = button.getAttribute('aria-label')!
    button.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${light ? '<path d="M20 14a8 8 0 0 1-10-10 8 8 0 1 0 10 10Z"/>' : '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>'}</svg>`
  }
  button.addEventListener('click', () => {
    const theme = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem('ag-theme', theme) } catch { /* storage may be disabled */ }
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'light' ? '#ffffff' : '#000000')
    document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', theme)
  })
  new MutationObserver(label).observe(document.documentElement, { attributes: true, attributeFilter: ['lang', 'data-theme'] })
  label()
  return button
}
