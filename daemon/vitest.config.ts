import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'
import { dirname, isAbsolute, resolve } from 'node:path'

const distDirectory = fileURLToPath(new URL('./dist/', import.meta.url))

export default defineConfig({
  plugins: [
    {
      name: 'daemon-compiled-test-modules',
      enforce: 'pre',
      resolveId(source, importer) {
        if (source.includes('?') || (!source.startsWith('.') && !isAbsolute(source) && !source.startsWith('file:'))) {
          return
        }
        const path = source.startsWith('file:')
          ? fileURLToPath(source)
          : isAbsolute(source)
            ? source
            : importer
              ? resolve(dirname(importer.split('?')[0]!), source)
              : undefined
        // V8 must not merge transformed test scripts with raw child scripts before source mapping.
        if (path?.startsWith(distDirectory) && path.endsWith('.js')) {
          return `${path}?vitest`
        }
      },
    },
  ],
  resolve: {
    alias: [
      {
        find: /^(\.\.\/)+src\/(.+)\.js$/,
        replacement: `${distDirectory}$2.js?vitest`,
      },
    ],
  },
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    maxWorkers: 4,
    testTimeout: 20_000,
    coverage: {
      provider: 'v8',
      autoAttachSubprocess: true,
      include: ['src/**/*.ts', 'dist/**/*.js'],
      exclude: ['**/*.d.ts'],
      excludeAfterRemap: true,
      reporter: ['text', 'json', 'lcov'],
      thresholds: {
        lines: 90,
        statements: 90,
        functions: 90,
      },
    },
  },
})
