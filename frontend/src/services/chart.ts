export type Pt = readonly [number, number]

/** Smooth line through points using midpoint quadratic curves. */
export function smoothPath(p: Pt[]): string {
  if (p.length < 2) return ''
  let d = `M${p[0][0].toFixed(1)},${p[0][1].toFixed(1)}`
  for (let i = 1; i < p.length; i++) {
    const mx = (p[i - 1][0] + p[i][0]) / 2
    const my = (p[i - 1][1] + p[i][1]) / 2
    d += ` Q${p[i - 1][0].toFixed(1)},${p[i - 1][1].toFixed(1)} ${mx.toFixed(1)},${my.toFixed(1)}`
  }
  const last = p[p.length - 1]
  return `${d} T${last[0].toFixed(1)},${last[1].toFixed(1)}`
}
