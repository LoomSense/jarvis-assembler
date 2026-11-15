/** A lightweight original interpretation of the reference's flowing azure star ribbon. */
export function mountStars(hero: HTMLElement): void {
  const canvas = document.createElement('canvas')
  canvas.className = 'hero-stars'
  canvas.setAttribute('aria-hidden', 'true')
  hero.prepend(canvas)
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  let seed = 41
  const random = () => ((seed = seed * 16807 % 2147483647) - 1) / 2147483646
  const stars = Array.from({ length: 950 }, (_, i) => ({ x: random(), y: random(), r: .3 + random() * 1.15, phase: random() * 6.28, ribbon: i < 700 }))
  const reduced = matchMedia('(prefers-reduced-motion: reduce)')
  let width = 0, height = 0, frame = 0, visible = true, time = 0, previous = 0
  const draw = () => {
    ctx.clearRect(0, 0, width, height)
    const light = document.documentElement.dataset.theme === 'light'
    for (const s of stars) {
      const x = (s.x + time * .008) % 1
      const y = s.ribbon ? .74 - .2 * Math.sin(x * 4 - 1) + (s.y - .5) * .24 : s.y
      const alpha = (.2 + .3 * (1 + Math.sin(s.phase + time * .6)) / 2) * (s.ribbon ? 1 : .5) * (light ? .45 : 1)
      ctx.fillStyle = `rgba(${light ? '42,109,181' : '158,207,255'},${alpha})`
      ctx.beginPath()
      ctx.arc(x * width, y * height, s.r, 0, Math.PI * 2)
      ctx.fill()
    }
  }
  const tick = (now: number) => {
    frame = 0
    if (!visible || document.hidden || reduced.matches) return
    if (now - previous > 32) { time += Math.min((now - previous) / 1000, .05); previous = now; draw() }
    frame = requestAnimationFrame(tick)
  }
  const sync = () => { cancelAnimationFrame(frame); frame = 0; draw(); if (visible && !document.hidden && !reduced.matches) frame = requestAnimationFrame(tick) }
  new ResizeObserver(() => {
    width = hero.clientWidth; height = hero.clientHeight
    const dpr = Math.min(devicePixelRatio, 2)
    canvas.width = width * dpr; canvas.height = height * dpr
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0); sync()
  }).observe(hero)
  new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; sync() }).observe(hero)
  new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  reduced.addEventListener('change', sync)
  document.addEventListener('visibilitychange', sync)
  window.addEventListener('pagehide', () => cancelAnimationFrame(frame))
  window.addEventListener('pageshow', sync)
}
