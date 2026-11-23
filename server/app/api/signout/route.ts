import { NextResponse } from 'next/server'
import { cookies } from 'next/headers'
import { COOKIE, cookie, current, save } from '@/lib/listener'

/** the module's sign-out: the listener's row is erased, not just the cookie */
export async function POST() {
  const found = await current()
  if (found) await save(found.id, {})
  ;(await cookies()).set(cookie(COOKIE, '', 0))
  return NextResponse.json({ ok: true }, { headers: { 'cache-control': 'no-store' } })
}
