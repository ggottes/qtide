import { cookies } from 'next/headers'
import { read, write, type Listener } from './store'
import { sign, verify } from './crypto'

/** the cookie the site and the module share: a random device id, signed, http-only */
export const COOKIE = 'qtide_pool'

export function newId(): string {
  return crypto.randomUUID().replace(/-/g, '')
}

/** the options for the device cookie; maxAge 0 is how a sign-out clears it */
export function cookie(name: string, value: string, maxAge = 31_536_000) {
  return {
    name,
    value,
    httpOnly: true,
    sameSite: 'lax' as const,
    maxAge,
    path: '/',
    secure: process.env.NODE_ENV === 'production',
  }
}

/** the listener this request belongs to, or null */
export async function current(): Promise<{ id: string; listener: Listener } | null> {
  const jar = await cookies()
  const id = verify(jar.get(COOKIE)?.value)
  if (!id) return null
  const listener = await read(id)
  return listener ? { id, listener } : null
}

export async function save(id: string, listener: Listener): Promise<void> {
  await write(id, listener)
}
