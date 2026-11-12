'use strict'

const vscode = require('vscode')
const nodeHttp = require('node:http')
const { createBridgeHost } = require('./bridge.cjs')

// 私有 bridge 只与 daemon loopback 端通信：使用不带 proxyEnv 的独占 Agent，绝不读取环境代理，
// 避免 NODE_USE_ENV_PROXY=1 把携带 bridgeSecret 的请求转发给代理。
const directAgent = new nodeHttp.Agent({ keepAlive: false })
const directHttp = {
  request: (url, options, callback) => nodeHttp.request(url, Object.assign({}, options, { agent: directAgent }), callback),
  get: (url, options, callback) => nodeHttp.get(url, Object.assign({}, options, { agent: directAgent }), callback),
}

let host

function activate(context) {
  host = createBridgeHost(vscode, { http: directHttp })
  host.start()
  context.subscriptions.push({
    dispose() {
      if (host) {
        host.dispose()
        host = undefined
      }
      directAgent.destroy()
    },
  })
}

function deactivate() {
  if (host) {
    host.dispose()
    host = undefined
  }
  directAgent.destroy()
}

module.exports = { activate, deactivate }
