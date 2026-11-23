import { NextResponse } from 'next/server'
import { current } from '@/lib/listener'

/** who this device is signed in as; 401 is the module's cue to show the sign-in */
export async function GET() {
  const found = await current()
  if (!found) return NextResponse.json({ error: 'signed out' }, { status: 401 })
  const { listener } = found
  if (!listener.shared) return NextResponse.json({ error: 'not shared' }, { status: 403 })
  return NextResponse.json(
    { name: listener.name || 'Listener', plan: listener.plan ?? null, country: listener.country ?? null },
    { headers: { 'cache-control': 'no-store' } },
  )
}
