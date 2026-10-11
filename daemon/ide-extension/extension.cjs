'use strict'

const vscode = require('vscode')
const { createBridgeHost } = require('./bridge.cjs')

let host

function activate(context) {
  host = createBridgeHost(vscode)
  host.start()
  context.subscriptions.push({
    dispose() {
      if (host) {
        host.dispose()
        host = undefined
      }
    },
  })
}

function deactivate() {
  if (host) {
    host.dispose()
    host = undefined
  }
}

module.exports = { activate, deactivate }
