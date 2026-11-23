import { NextResponse } from 'next/server'
import { current } from '@/lib/listener'
import * as resolve from '@/lib/resolve'

/**
 * The link a Mochy player opens. Qobuz first, as the pool's rule: one plain FLAC link, best tier
 * the listener's plan allows, so downloads (which want one whole file) work too.
 */
export async function GET(request: Request) {
  const found = await current()
  if (!found) return NextResponse.json({ error: 'signed out' }, { status: 401 })
  if (!found.listener.shared) return NextResponse.json({ error: 'not shared' }, { status: 403 })
  const url = new URL(request.url)
  const source = url.searchParams.get('source') ?? 'qobuz'
  const id = url.searchParams.get('id') ?? ''
  const formats = url.searchParams.get('formats') ?? '27,7,6'
  if (!id) return NextResponse.json({ error: 'id is required' }, { status: 400 })
  const stream = await resolve.stream({ id: found.id, listener: found.listener }, source, id, formats)
  if (!stream) return NextResponse.json({ error: 'no stream' }, { status: 404 })
  return NextResponse.json(stream, { headers: { 'cache-control': 'no-store' } })
}
