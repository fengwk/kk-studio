import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const browserTestsDir = path.resolve(__dirname, 'browser-tests')

const harnessInputs = Object.fromEntries(
  fs
    .readdirSync(browserTestsDir)
    .filter((file) => file.endsWith('-harness.html'))
    .sort()
    .map((file) => [
      file.replace(/\.html$/, ''),
      path.resolve(browserTestsDir, file),
    ])
)

const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/layout-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: harnessInputs,
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
  },
}

export default config
