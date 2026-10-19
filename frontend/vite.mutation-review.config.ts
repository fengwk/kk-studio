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
    outDir: path.resolve(__dirname, '../reports/mutation-review-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        'mutation-review-harness': path.resolve(__dirname, 'browser-tests/mutation-review-harness.html'),
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 5178,
    strictPort: true,
  },
}

export default config
