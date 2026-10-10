import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/loading-skeleton-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        'loading-skeleton-harness': path.resolve(__dirname, 'browser-tests/loading-skeleton-harness.html'),
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 5189,
    strictPort: true,
  },
}

export default config
