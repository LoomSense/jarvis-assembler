import './styles/nav.css'
import { themeButton } from './theme'
import { appLink } from './routes'

// The same two ways out on every screen (not in the headset): the workbench with its shelf of guides, and
// "new guide". Each screen was written as its own experience (src/main.ts); this is what ties them into one app.
// The demo key and the language travel with every link.

export type Here = 'home' | 'ready' | 'build' | 'guide'

const WORDS = {
  el: { nav: 'Πλοήγηση', home: 'Οι οδηγοί μου', build: 'Νέος οδηγός' },
  en: { nav: 'Navigation', home: 'My guides', build: 'New guide' },
}
const ICON = {
  // a house; a plus
  home: '<path d="M4 11.5 12 5l8 6.5M6.5 10v9h11v-9" />',
  build: '<path d="M12 5.5v13M5.5 12h13" />',
}

export function mountNav(here: Here): void {
  if (document.getElementById('appnav')) return
  const nav = document.createElement('nav')
  nav.id = 'appnav'
  const item = (name: 'home' | 'build', current: boolean) => {
    const a = document.createElement('a')
    a.dataset.to = name
    a.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${ICON[name]}</svg><span></span>`
    if (current) a.setAttribute('aria-current', 'page')
    // read the address when it is used, so a language chosen on the screen since then is kept
    a.addEventListener('click', (e) => {
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return
      e.preventDefault()
      if (!(name === 'build' && here === 'build' && !new URLSearchParams(location.search).get('job'))) location.assign(appLink(name === 'build' ? { mode: 'build' } : {}, name === 'home' ? 'guides' : ''))
    })
    a.href = appLink(name === 'build' ? { mode: 'build' } : {}, name === 'home' ? 'guides' : '')
    nav.append(a)
    return a
  }
  const home = item('home', here === 'home')
  // a job's feed is also `mode=build`: from there "new guide" leads to the empty drop screen
  const build = item('build', here === 'build' && !new URLSearchParams(location.search).get('job'))
  const label = () => {
    if (here === 'build' && !new URLSearchParams(location.search).get('job')) build.setAttribute('aria-current', 'page')
    else build.removeAttribute('aria-current')
    home.href = appLink({}, 'guides')
    build.href = appLink({ mode: 'build' })
    const w = WORDS[document.documentElement.lang === 'en' ? 'en' : 'el']
    nav.setAttribute('aria-label', w.nav)
    for (const [a, text] of [[home, w.home], [build, w.build]] as const) {
      a.setAttribute('aria-label', text)
      a.querySelector('span')!.textContent = text // the words are always shown on a wide screen: no mystery icons
    }
  }
  label()
  new MutationObserver(label).observe(document.documentElement, { attributes: true, attributeFilter: ['lang', 'data-state'] })
  nav.append(themeButton())
  document.body.append(nav)
}
