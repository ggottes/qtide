import crypto from 'node:crypto'

/**
 * Qobuz, the way the module does it — ported, because the phone now asks this server instead.
 *
 * The app id and secrets come from play.qobuz.com's own bundle (cached an hour), and a stream is a
 * signed CDN URL. Qobuz is the pool's first choice for every song: its tracks are plain FLAC, and
 * its 27/7/6 ladder covers hi-res down to CD without the DASH stitching Tidal needs.
 */
const API = 'https://www.qobuz.com/api.json/0.2'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

type Keys = { appId: string; secrets: string[] }
let keys: { at: number; value: Keys } | null = null

export async function appKeys(): Promise<Keys> {
  if (keys && Date.now() - keys.at < 3_600_000) return keys.value
  const login = await fetch('https://play.qobuz.com/login', { headers: { 'user-agent': UA } })
  if (!login.ok) throw new Error(`qobuz login page ${login.status}`)
  const html = await login.text()
  const bundlePath = /<script src="(\/resources\/[^"]+bundle\.js)"><\/script>/.exec(html)?.[1]
  if (!bundlePath) throw new Error('qobuz bundle not found')
  const bundle = await fetch(`https://play.qobuz.com${bundlePath}`, { headers: { 'user-agent': UA } })
  const text = await bundle.text()
  const appId = /production:\{api:\{appId:"(\d{9})"/.exec(text)?.[1]
  if (!appId) throw new Error('qobuz app id not found')
  const seeds = [...text.matchAll(/[a-z]\.initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)/g)].map((m) => [m[2], m[1]] as const)
  const infos = new Map(
    [...text.matchAll(/name:"\w+\/([A-Za-z]+)",info:"([\w=]+)",extras:"([\w=]+)"/g)].map((m) => [m[1].toLowerCase(), [m[2], m[3]]] as const),
  )
  const secrets: string[] = []
  for (const [tz, seed] of seeds) {
    const info = infos.get(tz)
    if (!info) continue
    let raw = seed + info[0] + info[1]
    raw = raw.slice(0, -44)
    raw += '='.repeat((4 - (raw.length % 4)) % 4)
    try {
      secrets.push(Buffer.from(raw, 'base64').toString('utf8'))
    } catch {}
  }
  if (!secrets.length) throw new Error('qobuz secrets not found')
  keys = { at: Date.now(), value: { appId, secrets } }
  return keys.value
}

type Answer = { ok: boolean; status: number; body: any; text: string }

async function call(endpoint: string, params: Record<string, string>, userToken: string): Promise<Answer> {
  const { appId } = await appKeys()
  const query = new URLSearchParams({ ...params, app_id: appId }).toString()
  const res = await fetch(`${API}/${endpoint}?${query}`, {
    headers: { 'X-User-Auth-Token': userToken, 'X-App-Id': appId, 'user-agent': UA },
    cache: 'no-store',
  })
  const text = await res.text()
  let body: any = null
  try { body = JSON.parse(text) } catch {}
  return { ok: res.ok, status: res.status, body, text }
}

export type Account = { id: number; name: string; plan?: string; country?: string }

export async function me(userToken: string): Promise<Account | null> {
  const answer = await call('user/get', {}, userToken)
  if (!answer.ok || !answer.body) return null
  const sub = answer.body.subscription ?? {}
  return {
    id: Number(answer.body.id ?? 0),
    name: String(answer.body.display_name || answer.body.login || answer.body.email || 'Qobuz'),
    plan: String(sub.offer || sub.label || '') || undefined,
    country: String(answer.body.country_code || '') || undefined,
  }
}

export type Track = { id: string; title: string; artist: string; album?: string; durationMs?: number; isrc?: string; source: 'qobuz' }

export async function search(userToken: string, query: string, limit = 8): Promise<Track[]> {
  const answer = await call('track/search', { query, limit: String(limit) }, userToken)
  const items = answer.body?.tracks?.items
  if (!Array.isArray(items)) return []
  return items.map((o: any) => ({
    id: String(o.id),
    title: String(o.title ?? ''),
    artist: String(o.performer?.name ?? o.album?.artist?.name ?? ''),
    album: o.album?.title ?? undefined,
    durationMs: o.duration ? o.duration * 1000 : undefined,
    isrc: o.isrc ?? undefined,
    source: 'qobuz' as const,
  }))
}

export type Stream = { url: string; label: string; codec: string; sampleRate?: number }

const FORMATS: Record<string, number[]> = {
  // what the module's chip asked for, as qobuz's own format ids
  '27,7,6': [27, 7, 6],
  '6': [6],
  '128': [5],
}

export async function stream(userToken: string, trackId: string, formats: string): Promise<Stream | null> {
  const { appId, secrets } = await appKeys()
  const ladder = FORMATS[formats] ?? [6]
  for (const format of ladder) {
    const ts = Math.floor(Date.now() / 1000)
    const signed = `trackgetFileUrlformat_id${format}intentstreamtrack_id${trackId}${ts}`
    for (const secret of secrets) {
      const sig = crypto.createHash('md5').update(signed + secret).digest('hex')
      const answer = await call(
        'track/getFileUrl',
        { app_id: appId, request_ts: String(ts), request_sig: sig, track_id: trackId, format_id: String(format), intent: 'stream' },
        userToken,
      )
      const url = answer.body?.url
      if (!url) continue
      const bits = Number(answer.body?.bit_depth ?? 0) || (format === 6 ? 16 : 24)
      const raw = Number(answer.body?.sampling_rate ?? 0) || (format === 6 ? 44.1 : 96)
      const rate = raw < 1000 ? Math.round(raw * 1000) : Math.round(raw)
      const khz = rate % 1000 === 0 ? String(rate / 1000) : (rate / 1000).toFixed(1)
      return { url, label: `${bits}-bit · ${khz} kHz · FLAC`, codec: 'FLAC', sampleRate: rate }
    }
  }
  return null
}
