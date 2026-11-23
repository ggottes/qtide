import { NextResponse } from 'next/server'
import { current } from '@/lib/listener'
import * as resolve from '@/lib/resolve'

/** what the module's "find the song" step asks: the best recording on the listener's own account */
export async function GET(request: Request) {
  const found = await current()
  if (!found) return NextResponse.json({ error: 'signed out' }, { status: 401 })
  if (!found.listener.shared) return NextResponse.json({ error: 'not shared' }, { status: 403 })
  const url = new URL(request.url)
  const query = (url.searchParams.get('q') ?? '').trim()
  if (!query) return NextResponse.json({ error: 'q is required' }, { status: 400 })
  const durationMs = Number(url.searchParams.get('durationMs') ?? 0) || undefined
  const isrc = url.searchParams.get('isrc') ?? undefined
  const best = await resolve.search(
    { id: found.id, listener: found.listener },
    query,
    // the module sends the title inside q; the match is scored against the whole query, which is
    // close enough because both sides strip punctuation the same way
    { title: query, durationMs },
  )
  if (!best) return NextResponse.json({ error: 'nothing matched' }, { status: 404 })
  return NextResponse.json({ track: best.track, via: best.via, isrc }, { headers: { 'cache-control': 'no-store' } })
}
