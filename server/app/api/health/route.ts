import { NextResponse } from 'next/server'

/** what the module asks before it keeps a server: a Qtide pool says so */
export async function GET() {
  return NextResponse.json({ qtide: 'pool', version: 1 }, { headers: { 'cache-control': 'no-store' } })
}
