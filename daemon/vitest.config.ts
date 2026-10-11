import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'
import { dirname, isAbsolute, resolve } from 'node:path'

const distDirectory = fileURLToPath(new URL('./dist/', import.meta.url))

/**
 * 判定一个已解析的绝对路径是否落在编译产物目录内。
 *
 * <p>Vite 规范化 id 用 `/`，而 Windows 上 `fileURLToPath` 产出 `\`；两侧都归一到 `/` 再比较，否则 dist 内部 import 在
 * Windows 上会漏掉 `?vitest` 标记，V8 会在 source map 之前把被转换的测试脚本和子进程的原始脚本合并，覆盖率坐标错位。
 */
export function isCompiledModulePath(candidate: string, directory: string): boolean {
  const normalized = candidate.replace(/\\/g, '/')
  const prefix = directory.replace(/\\/g, '/').replace(/\/+$/, '')
  return normalized.startsWith(`${prefix}/`) && normalized.endsWith('.js')
}

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
        if (path !== undefined && isCompiledModulePath(path, distDirectory)) {
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
