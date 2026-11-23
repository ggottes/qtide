import type { ReactNode } from 'react'
import './globals.css'

export const metadata = {
  title: 'Qtide — pool',
  description: 'Your own Tidal and Qobuz, behind one link for Mochy',
}

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <main>{children}</main>
      </body>
    </html>
  )
}
