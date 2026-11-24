import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { build, entry, entryTag, previewConfig, readIndex, reportsDir, styleMarker, workspaceStyles } from './vite.config.mjs'

const [bundle] = await build({
  ...previewConfig(),
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    lib: { entry, name: 'KKStudioWorkspacePreview', formats: ['iife'], fileName: 'workspace', cssFileName: 'workspace' },
    cssCodeSplit: false,
    target: 'es2022',
    reportCompressedSize: false,
    write: false,
  },
})
const script = bundle.output.find((file) => file.type === 'chunk' && file.isEntry)
const stylesheet = bundle.output.find((file) => file.type === 'asset' && file.fileName === 'workspace.css')
if (!script || !stylesheet) throw new Error('Expected one prototype script and stylesheet')
let js = script.code
for (const asset of bundle.output) {
  if (asset === script || asset === stylesheet) continue
  if (asset.type !== 'asset' || !asset.fileName.endsWith('.js')) throw new Error(`Unbundled asset: ${asset.fileName}`)
  const workerUrl = `/${asset.fileName}`
  if (!js.includes(workerUrl)) throw new Error(`Worker URL not found: ${asset.fileName}`)
  const data = `data:text/javascript;base64,${Buffer.from(asset.source).toString('base64')}`
  js = js.replaceAll(`.${workerUrl}`, data).replaceAll(workerUrl, data)
}
const index = await readIndex()
if (!['</head>', '</body>', entryTag, styleMarker].every((marker) => index.includes(marker))) {
  throw new Error('Unexpected HTML skeleton')
}
const css = Buffer.from(stylesheet.source).toString('utf8')
const workspaceCss = (await Promise.all(workspaceStyles.map((file) => readFile(file, 'utf8')))).join('\n')
const html = index.replace('</head>', () => `<style>${css.replace(/<\/style/gi, '<\\/style')}</style>\n</head>`)
  .replace(styleMarker, () => `<style>\n${workspaceCss}</style>`)
  .replace(entryTag, '<script type="module">KKStudioWorkspacePreview.mountPrototype();</script>')
  .replace('</body>', () => `<script>${js.replace(/<\/script/gi, '<\\/script')}</script>\n</body>`)
await mkdir(reportsDir, { recursive: true })
const output = path.join(reportsDir, 'kk-studio-workspace.html')
await writeFile(output, html)
console.log(output)
