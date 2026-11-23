import { open, seal } from './crypto'
import { read, write } from './store'
import * as qobuz from './qobuz'
import * as tidal from './tidal'

/**
 * One request from a module, answered from a listener's own keys.
 *
 * Qobuz is asked first for every song: its files are plain FLAC links, its ladder covers hi-res
 * down to CD, and it needs no DASH stitching — which is what makes downloads work. Tidal is the
 * second opinion, for a song Qobuz doesn't have.
 */
export type Look = { id: string; listener: Record<string, any> }

const words = (t: { title: string; artist: string }) =>
  `${t.title} ${t.artist}`.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()

function close(a: { title: string; artist: string }, b: { title: string; artist: string }): number {
  const at = new Set(words(a).split(' ').filter(Boolean))
  const bt = new Set(words(b).split(' ').filter(Boolean))
  if (!at.size || !bt.size) return 0
  let hit = 0
  for (const word of at) if (bt.has(word)) hit++
  return hit / Math.max(at.size, bt.size)
}

function pick<T extends { title: string; artist: string; durationMs?: number }>(items: T[], want: { title: string; artists?: string[]; durationMs?: number }): T | null {
  if (!items.length) return null
  const target = { title: want.title, artist: (want.artists ?? []).join(', ') }
  let best: { item: T; score: number } | null = null
  for (const item of items) {
    let score = close(item, target)
    if (want.durationMs && item.durationMs) {
      const delta = Math.abs(item.durationMs - want.durationMs) / want.durationMs
      if (delta < 0.04) score += 0.25
      else if (delta > 0.2) score -= 0.3
    }
    if (!best || score > best.score) best = { item, score }
  }
  return best && best.score >= 0.5 ? best.item : null
}

export async function search(look: Look, query: string, want: { title: string; artists?: string[]; durationMs?: number }) {
  const { listener } = look
  if (listener.qobuz) {
    try {
      const items = await qobuz.search(open(listener.qobuz), query, 8)
      const best = pick(items, want)
      if (best) return { track: best, via: 'qobuz' as const }
    } catch {}
  }
  if (listener.tidal) {
    try {
      const session = await tidal.login(open(listener.tidal))
      if (session) {
        const items = await tidal.search(session, query, 8)
        const best = pick(items, want)
        if (best) return { track: best, via: 'tidal' as const }
      }
    } catch {}
  }
  return null
}

export async function stream(look: Look, source: string, id: string, formats: string) {
  const { listener } = look
  const order = source === 'tidal' ? ['tidal', 'qobuz'] : ['qobuz', 'tidal']
  for (const service of order) {
    try {
      if (service === 'qobuz' && listener.qobuz) {
        const found = await qobuz.stream(open(listener.qobuz), id, formats)
        if (found) return { ...found, source: 'qobuz' }
      }
      if (service === 'tidal' && listener.tidal) {
        const session = await tidal.login(open(listener.tidal))
        if (!session) continue
        const found = await tidal.stream(session, id, formats === '6' ? 'lossless' : 'hires')
        if (found) {
          return {
            url: found.url,
            label: found.label,
            codec: found.codec,
            sampleRate: found.sampleRate,
            source: 'tidal',
            manifest: found.manifest,
            manifestText: found.manifestText,
          }
        }
      }
    } catch {}
  }
  return null
}

/** checks a key and remembers what the account is, so the site can show it without another call */
export async function checkQobuz(look: Look, token: string) {
  const account = await qobuz.me(token)
  if (!account) return null
  look.listener.qobuz = seal(token)
  look.listener.plan = account.plan
  look.listener.country = account.country
  await write(look.id, look.listener)
  return account
}

export async function checkTidal(look: Look, refresh: string) {
  const session = await tidal.login(refresh)
  if (!session) return null
  look.listener.tidal = seal(refresh)
  look.listener.country = session.country
  await write(look.id, look.listener)
  return { country: session.country }
}

export async function me(id: string) {
  const listener = await read(id)
  return listener
}
