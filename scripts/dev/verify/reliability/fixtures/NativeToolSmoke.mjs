// scripts/dev/verify/reliability/fixtures/NativeToolSmoke.mjs
//
// 在 reliability Daemon 镜像内直接验证**打包后的** Node coding tools，不经过 Provider、App
// command batch 或 Agent。断言只使用发行目录里真实存在的编译产物（`main.js` 同级的
// `coding/*.js` 与 `native/*.js`）与其生产依赖，因此它验证的是容器里那一份发行物：
//   * 原生进程 supervisor（koffi 预编译模块）能在本镜像的 libc/libstdc++ 下加载；
//   * find 的 segment-aware glob、转义字符类与 .gitignore（含嵌套否定）行为；
//   * grep 的 path:line 投影、多行匹配、非法正则与二进制输入的显式失败；
//   * process.exec 在真实会话范围内执行命令并回传可读输出。
//
// 用法（容器内，PATH 上的 node 即归档自带的冻结运行时）：
//   node /opt/kk-studio/daemon-dist-check/NativeToolSmoke.mjs
// 退出码 0 表示全部断言通过；任何断言失败都会打印具体不匹配并以非 0 退出。

import { mkdtemp, mkdir, rm, writeFile, realpath } from 'node:fs/promises'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const distDir = process.env.KK_STUDIO_DAEMON_DIST ?? '/opt/kk-studio/daemon'
const smokeRoot = process.env.KK_STUDIO_SMOKE_ROOT ?? '/workspace'

const load = async (relative) => import(pathToFileURL(join(distDir, relative)).href)
const { searchCapabilities, resolveSearchWorkerEntry } = await load('coding/search.js')
const { processCapabilities } = await load('coding/process.js')
const { createProcessSupervisor } = await load('native/index.js')

let assertions = 0

function check(condition, message) {
  assertions += 1
  if (!condition) {
    throw new Error(`native tool smoke assertion failed: ${message}`)
  }
}

function textOf(result) {
  return result.contents
    .filter((content) => content.type === 'text')
    .map((content) => content.text)
    .join('')
}

async function seed(root) {
  const write = async (relative, content) => {
    const file = join(root, relative)
    await mkdir(join(file, '..'), { recursive: true })
    await writeFile(file, content, 'utf8')
  }
  await write('.gitignore', 'ignored.txt\nignored-dir/\n.pi/git/*\n')
  await write('src/a.ts', 'const firstKeptEntryId = 7;\n')
  await write('src/nested/b.ts', 'export const nested = true;\n')
  await write('src/nested/c.md', 'firstKeptEntryId\n')
  await write('ignored.txt', 'firstKeptEntryId\n')
  await write('ignored-dir/hidden.ts', 'firstKeptEntryId\n')
  await write('.pi/git/.gitignore', '!.gitignore\n')
  await write('.pi/git/generated.txt', 'firstKeptEntryId\n')
  await write('.git/config', 'firstKeptEntryId\n')
  await write('d.txt', 'literal d\n')
  await write('1.txt', 'digit one\n')
  await write('multi.txt', 'alpha\nbeta\ngamma\n')
  await writeFile(join(root, 'binary.bin'), Buffer.from([0x78, 0x00, 0x79]))
}

async function main() {
  const root = await realpath(await mkdtemp(join(smokeRoot, '.native-tool-smoke-')))
  const processes = createProcessSupervisor()
  const signal = new AbortController().signal
  const context = {
    config: { dataDir: root, bashExecutable: 'bash' },
    signal,
    processes,
    resources: {
      async upload() {
        throw new Error('native tool smoke must not upload resources')
      },
    },
    async progress() {},
  }
  const invoke = async (capabilityId, args) => {
    const executor = searchCapabilities[capabilityId] ?? processCapabilities[capabilityId]
    if (executor === undefined) {
      throw new Error(`native tool smoke assertion failed: capability is not packaged: ${capabilityId}`)
    }
    return executor(
      {
        invocationId: `smoke-${capabilityId}`,
        capabilityId,
        arguments: args,
        timeoutMillis: 15000,
      },
      context,
    )
  }

  try {
    await seed(root)

    // Node 发行版的搜索能力按工具 schema 接收并回传**绝对路径**（Java 版按 workdir 相对路径回传）。
    const recursive = await invoke('fs.find', { pattern: '**/*.ts', path: root })
    check(
      textOf(recursive) === `${join(root, 'src/a.ts')}\n${join(root, 'src/nested/b.ts')}`,
      `recursive double-star find mismatch: ${textOf(recursive)}`,
    )

    const slashPattern = await invoke('fs.find', { pattern: 'src/*.ts', path: root })
    check(
      textOf(slashPattern) === join(root, 'src/a.ts'),
      `slash-containing find must remain segment-aware: ${textOf(slashPattern)}`,
    )

    // glob 由发行目录里的 picomatch 编译：`[d]` 是字面字符类，`\d` 是数字类。
    const literalClass = await invoke('fs.find', { pattern: '[d].txt', path: root })
    check(
      textOf(literalClass) === join(root, 'd.txt'),
      `literal glob character-class mismatch: ${textOf(literalClass)}`,
    )

    const digitClass = await invoke('fs.find', { pattern: '[\\d].txt', path: root })
    check(
      textOf(digitClass) === join(root, '1.txt'),
      `glob digit-class mismatch: ${textOf(digitClass)}`,
    )

    const ignored = await invoke('fs.find', { pattern: '*', path: root })
    check(!textOf(ignored).includes('ignored.txt'), 'root .gitignore file leaked')
    check(!textOf(ignored).includes('ignored-dir/hidden.ts'), 'ignored directory leaked')
    check(!textOf(ignored).includes('.git/config'), '.git metadata leaked')
    check(textOf(ignored).includes('.pi/git/.gitignore'), 'nested negation was not applied')
    check(!textOf(ignored).includes('.pi/git/generated.txt'), 'nested ignored file leaked')

    const grep = await invoke('fs.grep', {
      pattern: 'firstKeptEntryId',
      path: root,
      include: '**/*.ts',
    })
    check(
      textOf(grep) === `${join(root, 'src/a.ts')}:1:const firstKeptEntryId = 7;`,
      `grep path/line projection mismatch: ${textOf(grep)}`,
    )

    const multiline = await invoke('fs.grep', {
      pattern: 'alpha\nbeta',
      path: join(root, 'multi.txt'),
      multiline: true,
    })
    check(
      textOf(multiline) === `${join(root, 'multi.txt')}:1:alpha\n${join(root, 'multi.txt')}:2:beta`,
      `multiline grep mismatch: ${textOf(multiline)}`,
    )

    const invalid = await invoke('fs.grep', { pattern: '[', path: root })
    check(invalid.error, 'invalid regex must be an error')
    check(textOf(invalid).includes('Invalid regex'), 'invalid regex error must be explicit')

    const binary = await invoke('fs.grep', { pattern: 'x', path: join(root, 'binary.bin') })
    check(binary.error, 'direct binary grep must be an error')
    check(textOf(binary).includes('binary'), 'direct binary grep error must identify binary input')

    const ansi = await invoke('process.exec', {
      command: "printf '\\033[32mpassed\\033[0m\\n'",
      workdir: root,
    })
    check(ansi.error === false, `ANSI bash output must succeed: ${textOf(ansi)}`)
    check(textOf(ansi).includes('passed'), 'ANSI bash output must remain readable')

    // 发行目录必须自带编译后的搜索 Worker：缺它搜索会 fail-closed，而不是静默降级。
    check(resolveSearchWorkerEntry() !== null, 'the packaged search worker entry is missing')

    process.stdout.write(
      `native-node-tool-smoke=PASS assertions=${assertions} rg_fd_required=false\n`,
    )
  } finally {
    try {
      await processes.close()
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
}

await main()
