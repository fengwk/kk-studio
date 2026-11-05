import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { SkillPackageInstaller } from '../../../dist/skill/installer.js'

const installer = new SkillPackageInstaller(join(process.env.KK_PROBE_ROOT, 'skills'), {
  privateFs: {
    ensurePrivateDirectory: (dir) => mkdir(dir, { recursive: true }),
    writePrivateFile: (file, content) => writeFile(file, content),
  },
})
const state = await installer.install('test-package', 'a'.repeat(64), 1, process.env.KK_PROBE_URL)
process.stdout.write(state.error ?? '')
