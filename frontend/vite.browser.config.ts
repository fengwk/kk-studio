import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolveBrowserPort } from './browser-test-config'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const browserTestsDir = path.resolve(__dirname, 'browser-tests')

/** 动态登记 browser-tests 目录下全部静态 HTML harness（包含 compaction-card.html） */
export function collectHarnessInputs(): Record<string, string> {
  const files = fs
    .readdirSync(browserTestsDir)
    .filter((file) => file.endsWith('.html'))
    .sort()

  return Object.fromEntries(
    files.map((file) => [
      file.replace(/\.html$/, ''),
      path.resolve(browserTestsDir, file),
    ]),
  )
}

const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/browser-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: collectHarnessInputs(),
    },
  },
  server: {
    host: '127.0.0.1',
    port: resolveBrowserPort(),
    strictPort: true,
  },
  preview: {
    host: '127.0.0.1',
    port: resolveBrowserPort(),
    strictPort: true,
  },
}

export default config
