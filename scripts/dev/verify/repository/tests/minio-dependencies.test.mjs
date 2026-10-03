import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = path.resolve(fileURLToPath(new URL('../../../../..', import.meta.url)))
const read = (name) => readFileSync(path.join(root, name), 'utf8')
const client = 'deploy/dependencies/minio-client'
const version = 'RELEASE.2025-04-16T18-13-26Z'
const server = 'fengwk/minio@sha256:ea0a48a13c701cf2c4397b3a9c0fe9e10ed65b2f58f056bf07e2ce7308123535'
const hashes = {
  amd64: 'ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f',
  arm64: '61bb88e7435919834478ddd4d405a6de6d2c227079da5e8ee9655147398819a0',
}

test('all isolated stacks share the verified server pin and locally built client', () => {
  // 意图：单独更新某套栈、回退已撤下仓库、隐藏架构限制或冒用官方 mc 名字均立即失败。
  for (const stack of ['local', 'test', 'reliability', 'distributed']) {
    const compose = read(`deploy/${stack}/compose.yaml`)
    const service = (name) => compose.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\S|^\\S|(?![\\s\\S]))`, 'm'))?.[1]
    assert.ok(service('minio'), stack)
    assert.deepEqual([...service('minio').matchAll(/^\s+image: (.+)$/gm)].map((m) => m[1]), [server])
    assert.match(service('minio'), /^    platform: linux\/amd64$/m)
    assert.match(service('minio-init'), /^    build:\n      context: \.\.\/dependencies\/minio-client$/m)
    assert.match(service('minio-init'), new RegExp(`^    image: kk-studio-minio-client:${version}$`, 'm'))
    assert.match(service('minio-init'), /^    pull_policy: build$/m)
    assert.match(service('minio-init'), /entrypoint: \["\/bin\/sh", "-ec"\]/)
    assert.match(service('minio-init'), /mc anonymous set none/)
    assert.doesNotMatch(compose, /(?:minio\/minio|(?:quay\.io\/)?minio\/mc)(?=[:@])/)
  }
})

test('mc update guard fixes version, base index, offline checksums and licensing', () => {
  // 意图：版本与信任锚需协同审核；下载 checksum、可覆盖版本参数或运行层 curl 不可悄然引入。
  const dockerfile = read(`${client}/Dockerfile`)
  const bases = [...dockerfile.matchAll(/^FROM (\S+)/gm)].map((m) => m[1])
  assert.deepEqual(bases, Array(2).fill('alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8'))
  assert.deepEqual([...dockerfile.matchAll(/^ARG (\S+)/gm)].map((m) => m[1]), ['TARGETARCH'])
  assert.ok(dockerfile.includes(`https://github.com/minio/mc/releases/download/${version}/mc.linux-\${TARGETARCH}.${version}`))
  assert.match(dockerfile, /printf .+ "\$\{sha256\}" \| sha256sum -c -/)
  assert.doesNotMatch(dockerfile, /\.sha256sum/)
  assert.match(dockerfile, /COPY LICENSE NOTICE \/usr\/share\/licenses\/minio-client\//)
  assert.match(dockerfile, /ENTRYPOINT \["mc"\]/)
  assert.doesNotMatch(dockerfile.split(/^FROM /m)[2], /apk add|RUN /)
  for (const [file, digest] of Object.entries({
    LICENSE: '0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0',
    NOTICE: '56635f1740e8c33231e76ba8fbe098e7e9cc95d28b7b4abb98fed6270405810e',
  })) {
    assert.equal(createHash('sha256').update(read(`${client}/${file}`)).digest('hex'), digest)
  }
  const readme = read(`${client}/README.md`)
  for (const hash of Object.values(hashes)) {
    assert.ok(readme.includes(hash))
  }
  assert.ok(readme.includes(server))
  assert.ok(readme.includes('10d2f5bfeb6d3b0661a394a0017db67f44ad3c5d'))
  assert.ok(readme.includes(`${version}.tar.gz`))
})

test('the Dockerfile architecture selector returns reviewed anchors and rejects unknown platforms', () => {
  // 意图：执行实际 shell case，防止架构分支拼写或默认回退让错误二进制进入镜像。
  const selector = read(`${client}/Dockerfile`).match(/case "\$\{TARGETARCH\}" in ([\s\S]*?)esac/)[0].replaceAll('\\\n', '\n')
  for (const [arch, hash] of Object.entries(hashes)) {
    const result = execFileSync('/bin/sh', ['-ec', `${selector}; printf '%s' "$sha256"`], {
      env: { ...process.env, TARGETARCH: arch },
      encoding: 'utf8',
    })
    assert.equal(result, hash)
  }
  for (const arch of ['386', 'riscv64', '']) {
    assert.throws(() => execFileSync('/bin/sh', ['-ec', selector], {
      env: { ...process.env, TARGETARCH: arch },
      stdio: 'pipe',
    }), (error) => error.status === 1 && error.stderr.toString().includes('Unsupported mc architecture'))
  }
})
