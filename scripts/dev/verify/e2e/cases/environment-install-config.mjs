import { assert, envelopeData, expectHttpError, cid } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

/** Canonical non-trivial install settings shared by environment/configsync matrix cases. */
export function linuxInstallConfig() {
  return {
    operatingSystem: 'linux',
    javaHome: null,
    daemon: {
      studioUrl: 'http://127.0.0.1:18081',
      note: 'e2e install config',
      lsp: {
        servers: {
          jdtls: {
            command: ['/usr/bin/jdtls'],
            extensions: ['.java'],
            rootMarkers: ['pom.xml'],
            firstMatchMarkers: ['.git'],
          },
        },
      },
    },
  }
}

export function assertInstallConfig(config) {
  assert(config && typeof config === 'object', `installConfig must be an object: ${JSON.stringify(config)}`)
  assert(config.operatingSystem === 'linux', `operatingSystem must be linux: ${config.operatingSystem}`)
  assert(config.javaHome == null, `javaHome must stay unset: ${config.javaHome}`)
  assert(
    config.daemon?.studioUrl === 'http://127.0.0.1:18081',
    `studioUrl must round-trip: ${config.daemon?.studioUrl}`,
  )
  assert(config.daemon?.note === 'e2e install config', `note must round-trip: ${config.daemon?.note}`)
  const jdtls = config.daemon?.lsp?.servers?.jdtls
  assert(jdtls, `jdtls server must round-trip: ${JSON.stringify(config.daemon?.lsp)}`)
  assert(
    Array.isArray(jdtls.command)
      && jdtls.command.length === 1
      && jdtls.command[0] === '/usr/bin/jdtls',
    `command must round-trip: ${JSON.stringify(jdtls.command)}`,
  )
  assert(
    jdtls.extensions?.[0] === '.java'
      && jdtls.rootMarkers?.[0] === 'pom.xml'
      && jdtls.firstMatchMarkers?.[0] === '.git',
    `nested marker lists must round-trip: ${JSON.stringify(jdtls)}`,
  )
}

registerCase({
  id: 'environment.install_config_cas_roundtrip',
  level: 'L1',
  title: 'Environment installConfig PUT CAS 持久化与往返',
  docs: 'PUT /api/harness/environments/{id}/install-config 用 expectedVersion CAS 保存嵌套 installConfig； stale 版本 409、相同配置 no-op 不推进版本；硬非法 studioUrl/未知字段/缺 operatingSystem/null 配置 400 且不写入、不回显提交值，随后 GET 仍为已保存配置',
  async run(ctx) {
    const name = `e2e-install-cfg-${cid().slice(0, 8)}`
    const { json: createJson } = await ctx.call('POST', '/api/harness/environments', { name })
    const created = envelopeData(createJson)
    const environmentId = created.id
    assert(environmentId, `created environment must have id: ${JSON.stringify(createJson)}`)
    assert(
      created.installConfig == null,
      `new environment must not preconfigure install settings: ${JSON.stringify(created.installConfig)}`,
    )
    let version = String(created.version)
    const path = `/api/harness/environments/${encodeURIComponent(environmentId)}/install-config`

    try {
      // stale expectedVersion must conflict before any write
      await expectHttpError(
        () => ctx.call('PUT', path, { expectedVersion: '999999', installConfig: linuxInstallConfig() }),
        { status: 409 },
      )

      const { json: putJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        installConfig: linuxInstallConfig(),
      })
      const saved = envelopeData(putJson)
      assertInstallConfig(saved.installConfig)
      assert(
        String(saved.version) !== version,
        `persisting config must advance version (was ${version}, got ${saved.version})`,
      )
      version = String(saved.version)

      // re-saving the identical config is a CAS-checked no-op
      const { json: noopJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        installConfig: linuxInstallConfig(),
      })
      const noop = envelopeData(noopJson)
      assertInstallConfig(noop.installConfig)
      assert(
        String(noop.version) === version,
        `identical config must not advance version (was ${version}, got ${noop.version})`,
      )

      const { json: getJson } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}`,
      )
      assertInstallConfig(envelopeData(getJson).installConfig)

      const invalidBodies = [
        { expectedVersion: version, installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'ftp://nope' } } },
        {
          expectedVersion: version,
          installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'http://127.0.0.1:18081', unknown: 1 } },
        },
        { expectedVersion: version, installConfig: { javaHome: '/opt/jdk', daemon: { studioUrl: 'http://127.0.0.1:18081' } } },
        { expectedVersion: version, installConfig: null },
      ]
      for (const body of invalidBodies) {
        const error = await expectHttpError(() => ctx.call('PUT', path, body), { status: 400 })
        assert(!String(error.body).includes('ftp://nope'), 'validation error must not echo submitted values')
        assert(!String(error.body).includes('/opt/jdk'), 'validation error must not echo submitted values')
      }

      const { json: afterJson } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}`,
      )
      const after = envelopeData(afterJson)
      assert(
        String(after.version) === version,
        `invalid PUT must not advance version (was ${version}, got ${after.version})`,
      )
      assertInstallConfig(after.installConfig)

      // stale expectedVersion after a successful write also conflicts
      await expectHttpError(
        () => ctx.call('PUT', path, { expectedVersion: '0', installConfig: linuxInstallConfig() }),
        { status: 409 },
      )
    } finally {
      let versionToDelete = version
      try {
        const { json } = await ctx.call(
          'GET',
          `/api/harness/environments/${encodeURIComponent(environmentId)}`,
        )
        const current = envelopeData(json)
        if (current?.version != null) {
          versionToDelete = String(current.version)
        }
      } catch {
        // fall back to the last known version
      }
      await ctx.call(
        'DELETE',
        `/api/harness/environments/${encodeURIComponent(environmentId)}?expectedVersion=${encodeURIComponent(versionToDelete)}`,
      )
    }
  },
})
