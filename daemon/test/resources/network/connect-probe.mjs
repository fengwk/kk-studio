#!/usr/bin/env node
/**
 * 独立子进程 TLS 代理探针。
 *
 * 以默认 TLS 验证（信任 `NODE_EXTRA_CA_CERTS` 注入的测试 CA）经真实代理 CONNECT 访问 HTTPS 目标，把响应体
 * 写入 stdout。目标 URL 与代理地址只通过环境变量传入；脚本本身不包含任何凭据。
 */
import { installOutboundNetwork } from '../../../dist/network.js'

const url = process.env.KK_PROBE_URL
const proxy = process.env.KK_PROBE_PROXY
if (url === undefined || proxy === undefined) {
  process.stderr.write('missing probe configuration\n')
  process.exit(2)
}

const system = {
  async resolve() {
    return null
  },
  async close() {},
}

const installed = await installOutboundNetwork({ https_proxy: proxy }, system)
try {
  const body = await installed.network.withResponse(url, {}, new AbortController().signal, async (response) => {
    const chunks = []
    for await (const chunk of response.body) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
    }
    return Buffer.concat(chunks).toString('utf8')
  })
  process.stdout.write(body)
} catch (error) {
  process.stderr.write(`${error && error.message ? error.message : 'probe failed'}\n`)
  process.exitCode = 1
} finally {
  await installed.close()
}
