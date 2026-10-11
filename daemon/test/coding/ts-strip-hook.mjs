// 测试态加载 .ts Worker 的解析钩子：把相对的 `./x.js` 说明符重写为实际存在的 `./x.ts`。
//
// 仅由 test/coding/helpers.ts 通过 configureSearchWorker 注入（`--import`）；生产编译产物直接引用 ./search-worker.js，不引用本文件。
import { registerHooks } from 'node:module'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('.') && specifier.endsWith('.js') && context.parentURL !== undefined) {
      const candidate = new URL(specifier.replace(/\.js$/, '.ts'), context.parentURL)
      if (existsSync(fileURLToPath(candidate))) {
        return nextResolve(candidate.href, context)
      }
    }
    return nextResolve(specifier, context)
  },
})
