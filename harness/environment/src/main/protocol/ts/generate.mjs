import { execFileSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PROTOCOL_DIR = resolve(HERE, '..')
const PROTO_FILE = 'control.proto'
const REPO_ROOT = resolve(HERE, '../../../../../..')
const DAEMON_DIR = resolve(REPO_ROOT, 'daemon')
const GENERATED = resolve(DAEMON_DIR, 'src/generated/control_pb.ts')
const PLUGIN = resolve(DAEMON_DIR, 'node_modules/.bin/protoc-gen-es')
const PROTOBUF_VERSION = '4.36.2'
const PROTOC_VERSION = '36.2'
const CHECK = process.argv.includes('--check')

function classifier() {
  if (process.platform === 'linux' && process.arch === 'x64') return 'linux-x86_64'
  if (process.platform === 'linux' && process.arch === 'arm64') return 'linux-aarch_64'
  if (process.platform === 'darwin' && process.arch === 'x64') return 'osx-x86_64'
  if (process.platform === 'darwin' && process.arch === 'arm64') return 'osx-aarch_64'
  if (process.platform === 'win32' && process.arch === 'x64') return 'windows-x86_64'
  throw new Error(`unsupported protoc host: ${process.platform}/${process.arch}`)
}

function locateProtoc(temp) {
  const artifact = join(
    homedir(),
    '.m2',
    'repository',
    'com',
    'google',
    'protobuf',
    'protoc',
    PROTOBUF_VERSION,
    `protoc-${PROTOBUF_VERSION}-${classifier()}.exe`,
  )
  if (existsSync(artifact)) {
    const executable = join(temp, 'protoc')
    copyFileSync(artifact, executable)
    chmodSync(executable, 0o755)
    return executable
  }
  return 'protoc'
}

function normalizeGenerated(text) {
  return text.replace(/\n+$/, '\n')
}

function failVersion(output) {
  if (!output.includes(`libprotoc ${PROTOC_VERSION}`)) {
    throw new Error(
      `protoc ${PROTOC_VERSION} is required; found "${output.trim()}". ` +
        `Set up Maven artifact com.google.protobuf:protoc:${PROTOBUF_VERSION} or install the matching protoc.`,
    )
  }
}

if (!existsSync(PLUGIN)) {
  throw new Error(`protoc-gen-es is missing; run "npm --prefix ${DAEMON_DIR} ci"`)
}

const temp = mkdtempSync(join(tmpdir(), 'kk-studio-protocol-'))
try {
  const protoc = locateProtoc(temp)
  const version = execFileSync(protoc, ['--version'], { encoding: 'utf8' })
  failVersion(version)

  const out = join(temp, 'generated')
  mkdirSync(out, { recursive: true })
  execFileSync(
    protoc,
    [
      `--plugin=protoc-gen-es=${PLUGIN}`,
      `--es_out=${out}`,
      '--es_opt=target=ts',
      `-I${PROTOCOL_DIR}`,
      resolve(PROTOCOL_DIR, PROTO_FILE),
    ],
    { stdio: 'inherit' },
  )

  const generated = normalizeGenerated(readFileSync(join(out, 'control_pb.ts'), 'utf8'))
  if (CHECK) {
    const current = normalizeGenerated(readFileSync(GENERATED, 'utf8'))
    if (generated !== current) {
      throw new Error('generated control_pb.ts is stale; run "npm run generate"')
    }
    console.log('protoc-gen-es output is reproducible')
  } else {
    writeFileSync(GENERATED, generated)
    console.log(`wrote ${GENERATED}`)
  }
} finally {
  rmSync(temp, { recursive: true, force: true })
}
