/**
 * Tidal, ported from the module so the phone doesn't have to hold the key.
 *
 * The same two public client pairs: the CD pair mints the token every call uses, the hi-res pair
 * asks for the top tier. A song is checked against its own `audioModes` first, so a Hi-Res pick on a
 * CD-only master plays CD instead of getting AAC back with a lossless label.
 */
const AUTH = 'https://auth.tidal.com/v1/oauth2'
const API = 'https://api.tidal.com/v1'
const HIRES = { id: 'fX2JxdmntZWK0ixT', secret: '1Nm5AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAavxAg=' }
const CD = { id: '4N3n6Q1x95LL5K7p', secret: 'oKOXfJW371cX6xaZ0PyhgGNBdNLlBZd4AKKYougMjik=' }

type Session = { access: string; hires: string; country: string }

async function exchange(refresh: string, client: { id: string; secret: string }): Promise<string | null> {
  const res = await fetch(`${AUTH}/token`, {
    method: 'POST',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      authorization: 'Basic ' + Buffer.from(`${client.id}:${client.secret}`).toString('base64'),
    },
    body: new URLSearchParams({ client_id: client.id, refresh_token: refresh, grant_type: 'refresh_token', scope: 'r_usr+w_usr+w_sub' }).toString(),
    cache: 'no-store',
  })
  if (!res.ok) return null
  const body = await res.json().catch(() => null)
  return body?.access_token ?? null
}

export async function login(refresh: string): Promise<Session | null> {
  const access = await exchange(refresh, CD)
  if (!access) return null
  const hires = (await exchange(refresh, HIRES)) ?? access
  const me = await get(`${API}/sessions`, access)
  return { access, hires, country: String(me?.countryCode ?? 'US') }
}

async function get(url: string, token: string): Promise<any> {
  const res = await fetch(url, { headers: { authorization: `Bearer ${token}` }, cache: 'no-store' })
  if (!res.ok) return null
  return res.json().catch(() => null)
}

export type Track = { id: string; title: string; artist: string; album?: string; durationMs?: number; source: 'tidal' }

export async function search(session: Session, query: string, limit = 8): Promise<Track[]> {
  const url = `${API}/search/tracks?query=${encodeURIComponent(query)}&limit=${limit}&countryCode=${encodeURIComponent(session.country)}`
  const body = await get(url, session.access)
  const items: any[] = Array.isArray(body?.items) ? body.items : []
  return items.map((o) => ({
    id: String(o.id),
    title: String(o.title ?? ''),
    artist: Array.isArray(o.artists) ? o.artists.map((a: any) => a.name).filter(Boolean).join(', ') : '',
    album: o.album?.title,
    durationMs: o.duration ? o.duration * 1000 : undefined,
    source: 'tidal' as const,
  }))
}

export type Stream = { url: string; label: string; codec: string; sampleRate?: number; manifest: 'progressive' | 'dash'; manifestText?: string }

/** what the song actually has, so a tier is never requested that would come back as AAC */
async function plan(session: Session, trackId: string, quality: string): Promise<string[]> {
  const row = await get(`${API}/tracks/${trackId}?countryCode=${encodeURIComponent(session.country)}`, session.access)
  const modes: string[] = Array.isArray(row?.data?.[0]?.audioModes) ? row.data[0].audioModes : []
  if (!modes.length) return quality === 'lossless' ? ['LOSSLESS'] : ['HI_RES_LOSSLESS', 'LOSSLESS']
  const lossless = modes.includes('LOSSLESS') || modes.includes('HIRES_LOSSLESS')
  const hires = modes.includes('HIRES_LOSSLESS')
  if (quality === 'hires') {
    if (hires) return ['HI_RES_LOSSLESS', 'LOSSLESS']
    if (lossless) return ['LOSSLESS']
    return ['HIGH', 'LOW']
  }
  return lossless ? ['LOSSLESS'] : ['HIGH', 'LOW']
}

async function playback(session: Session, trackId: string, quality: string): Promise<any> {
  const token = quality.startsWith('HI_RES') || quality === 'HIGH' ? session.hires : session.access
  const url = `${API}/tracks/${trackId}/playbackinfopostpaywall?audioquality=${quality}&playbackmode=STREAM&assetpresentation=FULL&countryCode=${encodeURIComponent(session.country)}`
  return get(url, token)
}

export async function stream(session: Session, trackId: string, quality: string): Promise<Stream | null> {
  const found: Stream[] = []
  for (const step of await plan(session, trackId, quality)) {
    const body = await playback(session, trackId, step)
    if (!body?.manifest) continue
    let manifest = ''
    try {
      manifest = Buffer.from(String(body.manifest), 'base64').toString('utf8')
    } catch {
      continue
    }
    const rate = Number(/audioSamplingRate="(\d+)"/.exec(manifest)?.[1] ?? 96000) || 96000
    if (String(body.manifestMimeType ?? '').includes('dash') || manifest.trimStart().startsWith('<')) {
      found.push({ url: '', label: `${Math.round(rate / 1000)} kHz · FLAC`, codec: 'FLAC', sampleRate: rate, manifest: 'dash', manifestText: manifest })
      continue
    }
    let url = ''
    try {
      url = JSON.parse(manifest)?.urls?.[0] ?? ''
    } catch {}
    if (!url) continue
    const isFlac = String(JSON.parse(manifest)?.codecs ?? '').toLowerCase().includes('flac')
    found.push(
      isFlac
        ? { url, label: 'CD · FLAC', codec: 'FLAC', sampleRate: 44100, manifest: 'progressive' }
        : { url, label: step === 'LOW' ? 'AAC 96' : 'AAC 320', codec: 'AAC', sampleRate: 44100, manifest: 'progressive' },
    )
  }
  return found.find((s) => s.codec === 'FLAC') ?? found[found.length - 1] ?? null
}
