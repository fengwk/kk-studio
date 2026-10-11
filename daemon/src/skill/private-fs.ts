import type { PrivateFs } from './types.js'

export async function loadNativePrivateFs(
  importer: (specifier: string) => Promise<unknown> = (s) => import(s),
): Promise<PrivateFs> {
  const specifier = '../native/index.js'
  let mod: unknown
  try {
    mod = await importer(specifier)
  } catch (e) {
    throw new Error(`native private filesystem module is unavailable: ${(e as Error).message}`)
  }
  const m = mod as Partial<PrivateFs>
  if (typeof m.ensurePrivateDirectory !== 'function' || typeof m.writePrivateFile !== 'function') {
    throw new Error('native private filesystem module is missing required exports')
  }
  return { ensurePrivateDirectory: m.ensurePrivateDirectory, writePrivateFile: m.writePrivateFile }
}
