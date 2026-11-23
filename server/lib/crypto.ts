import crypto from 'node:crypto'

/**
 * The token vault: whoever's Tidal or Qobuz key is in the database is encrypted with AES-256-GCM
 * under KEY_SECRET, so a database dump alone is worth nothing.
 *
 * One key per server, set once in Vercel's env. Rotating it means every listener signs in again,
 * which is the honest trade for not needing a key-management service.
 */
function key(): Buffer {
  const raw = process.env.KEY_SECRET
  if (!raw) throw new Error('KEY_SECRET is not set')
  const bytes = Buffer.from(raw, raw.length === 64 ? 'hex' : 'base64')
  if (bytes.length !== 32) throw new Error('KEY_SECRET must be 32 bytes (64 hex chars or base64)')
  return bytes
}

export function seal(plain: string): string {
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key(), iv)
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()])
  return [iv, cipher.getAuthTag(), enc].map((b) => b.toString('base64url')).join('.')
}

export function open(sealed: string): string {
  const [iv, tag, data] = sealed.split('.').map((part) => Buffer.from(part, 'base64url'))
  const decipher = crypto.createDecipheriv('aes-256-gcm', key(), iv)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8')
}

/** a signed, http-only session cookie: the device's id, nothing secret in it */
export function sign(value: string): string {
  const mac = crypto.createHmac('sha256', key()).update(value).digest('base64url')
  return `${value}.${mac}`
}

export function verify(signed: string | undefined): string | null {
  if (!signed) return null
  const cut = signed.lastIndexOf('.')
  if (cut < 1) return null
  const value = signed.slice(0, cut)
  const mac = signed.slice(cut + 1)
  const want = crypto.createHmac('sha256', key()).update(value).digest('base64url')
  if (mac.length !== want.length) return null
  return crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(want)) ? value : null
}
