import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { composerSlashPreviewPlugin } from './composer-slash-preview-plugin.mjs'

export const prototypeDir = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(prototypeDir, '../../frontend')
export const reportsDir = path.resolve(prototypeDir, '../../reports/workspace-prototype')
const requireFrontend = createRequire(path.join(frontend, 'package.json'))
export const { build, normalizePath } = requireFrontend('vite')
const react = requireFrontend('@vitejs/plugin-react').default
const sourceRoot = normalizePath(path.join(frontend, 'src'))
const { dependencies } = requireFrontend('./package.json')
export const entry = path.join(prototypeDir, 'main.tsx')
export const entryTag = '<script type="module" src="./start.ts"></script>'
export const styleMarker = '<!-- workspace:styles -->'
export const workspaceStyles = ['shell.css', 'sidebar.css', 'panes.css'].map((name) => path.join(prototypeDir, 'workspace', name))
const indexFile = path.join(prototypeDir, 'index.html')
export const readIndex = () => readFile(indexFile, 'utf8')

// Vite resolves dependencies from the existing frontend install. The preview has its own
// HTML entry, no API/IDE proxy, and a different dev origin from the real application.
function previewEntry() {
  const fileHref = (file) => '/@fs/' + normalizePath(file).replace(/^\//, '')
  const browserEntry = fileHref(path.join(prototypeDir, 'start.ts'))
  const links = workspaceStyles.map((file) => `<link rel="stylesheet" href="${fileHref(file)}">`).join('\n')
  return {
    name: 'workspace-prototype-entry',
    handleHotUpdate({ file, server }) {
      if (normalizePath(file) !== normalizePath(indexFile)) return
      server.ws.send({ type: 'full-reload', path: '*' })
      return []
    },
    configureServer(server) {
      server.watcher.add(prototypeDir)
      server.middlewares.use(async (request, response, next) => {
        if (!['/', '/index.html'].includes(request.url?.split('?')[0])) return next()
        try {
          const html = (await readIndex()).replace(styleMarker, links)
            .replace(entryTag, `<script type="module" src="${browserEntry}"></script>`)
          const page = await server.transformIndexHtml('/index.html', html)
          response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          response.end(page)
        } catch (error) {
          next(error)
        }
      })
    },
  }
}

export function previewConfig() {
  return {
    root: frontend,
    configFile: false,
    publicDir: false,
    appType: 'custom',
    cacheDir: path.join(reportsDir, '.vite'),
    plugins: [
      previewEntry(),
      composerSlashPreviewPlugin(sourceRoot), react(),
    ],
    resolve: {
      dedupe: Object.keys(dependencies),
      alias: {
        '@/features/catalog/ai-model-capabilities': path.join(prototypeDir, 'mock/local-catalog.ts'),
        '@/shared/api/agent-service': path.join(prototypeDir, 'mock/local-catalog.ts'),
        '@/features/catalog/ai-model-pricing-preview': path.join(prototypeDir, 'catalog-editor-preview-plugin.mjs'),
        '@/features/settings/config-sync-service': path.join(prototypeDir, 'mock/local-config-sync.ts'),
        '@/features/environment/environment-runtime-query': path.join(prototypeDir, 'mock/local-environment-runtime.ts'),
        '@/features/thread/runtime/thread-panel/messages/MetaMessageBlock': path.join(prototypeDir, 'workspace/TurnFooter.tsx'),
        '@/features/thread/runtime/interactions/useApprovalDecision': path.join(prototypeDir, 'mock/local-approval-decision.ts'),
        '@preview-original-turn-footer': `${sourceRoot}/features/thread/runtime/thread-panel/messages/MetaMessageBlock.tsx`,
        '@': sourceRoot,
      },
    },
    optimizeDeps: { entries: [normalizePath(path.join(prototypeDir, 'start.ts'))] },
    server: {
      host: '127.0.0.1', port: 5176, strictPort: true,
      fs: { allow: [prototypeDir, frontend, reportsDir] },
    },
  }
}

export default previewConfig()
