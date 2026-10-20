/** scripts/dev/app.sh loads the external data-plane file only when asked, and fails closed. */

import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, symlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { functionBody, readText, runBash, shellQuote } from './support/shell.mjs'
import {
  DEV_SCRIPT,
  PREVIEW_MARKER,
  REQUIRED_KEYS,
  SHARED_PREVIEW_TEMPLATE,
  VALUE_PREFIX,
  cleanPreviewEnvironment,
  placeholderConfig,
  sourceDevScript,
  withTempDir,
  writeConfig,
} from './support/shared-preview.mjs'

const output = (result) => result.stdout + result.stderr

/** The caller-side switches a valid shared preview carries. */
const MARKER = {
  [PREVIEW_MARKER]: 'true',
  SPRING_PROFILES_ACTIVE: 'prod',
  SPRING_FLYWAY_ENABLED: 'false',
  KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED: 'false',
}

test('test intent: generic use without a config file is unchanged and reads no file', () => {
  const result = sourceDevScript('printf "%s\\n" "$SPRING_PROFILE" "$BACKEND_PORT" "$FRONTEND_PORT"')
  assert.equal(result.status, 0, output(result))
  assert.deepEqual(result.stdout.split(/\s+/).filter(Boolean), ['e2e', '18080', '5173'])
  assert.ok(!result.stdout.includes(VALUE_PREFIX))
})

test('test intent: allowed values reach only an explicit backend child', () => {
  // Parsed values stay shell-private until the Backend handoff and are then unexported.
  const passwordKey = 'KK_STUDIO_DB_PASSWORD'
  withTempDir((temporary) => {
    const config = writeConfig(temporary, `# comment line\n\n${passwordKey}=pa=ss=word\n`)
    const result = sourceDevScript(
      `if printenv ${passwordKey} >/dev/null; then echo PRE_EXPORTED; fi\n` +
        'set_loaded_shared_preview_env_exported true\n' +
        `bash -c 'printf "CHILD:%s\\\\n" "$${passwordKey}"'\n` +
        'set_loaded_shared_preview_env_exported false\n' +
        `if printenv ${passwordKey} >/dev/null; then echo POST_EXPORTED; fi\n` +
        `printf "RESULT:%s\\n" "$${passwordKey}"`,
      { SHARED_PREVIEW_ENV_FILE: config },
    )
    assert.equal(result.status, 0, output(result))
    // 只按第一个 `=` 拆分，值按字面量读取，不做任何 shell 求值。
    assert.ok(result.stdout.includes('CHILD:pa=ss=word'))
    assert.ok(result.stdout.includes('RESULT:pa=ss=word'))
    assert.ok(result.stdout.includes('values not printed'))
    assert.ok(!result.stdout.includes('PRE_EXPORTED'))
    assert.ok(!result.stdout.includes('POST_EXPORTED'))
  })
})

test('test intent: start exports the data plane only around the backend fork', () => {
  // Maven/npm/Vite must not inherit the owner-only data-plane credentials.
  const start = functionBody(DEV_SCRIPT, 'start_all')
  const handoff = functionBody(DEV_SCRIPT, 'run_backend_detached')

  assert.ok(start.includes('run_backend_detached "$BACKEND_LOG"'))
  assert.ok(start.includes('run_detached "$FRONTEND_LOG"'))
  assert.ok(!start.includes('run_backend_detached "$FRONTEND_LOG"'))
  assert.ok(handoff.includes('set_loaded_shared_preview_env_exported true'))
  assert.ok(handoff.includes('run_detached "$log_file" "$@"'))
  assert.ok(handoff.includes('set_loaded_shared_preview_env_exported false'))
})

test('test intent: CRLF line endings do not leak into values', () => {
  withTempDir((temporary) => {
    const config = writeConfig(
      temporary,
      'KK_STUDIO_DB_USER=crlf-user\r\nKK_STUDIO_DB_URL=jdbc:postgresql://host/db\r\n',
    )
    const result = sourceDevScript('printf "RESULT:[%s]" "$KK_STUDIO_DB_USER"', { SHARED_PREVIEW_ENV_FILE: config })
    assert.equal(result.status, 0, output(result))
    assert.ok(result.stdout.includes('RESULT:[crlf-user]'))
  })
})

test('test intent: file requirements are enforced before parsing', () => {
  // Absolute path, regular non-symlink file, owner-only mode and owner are all checked.
  withTempDir((root) => {
    const valid = writeConfig(root, placeholderConfig())

    const relative = runBash(`source ${shellQuote(DEV_SCRIPT)}\n:`, {
      cwd: root,
      env: { ...cleanPreviewEnvironment(), SHARED_PREVIEW_ENV_FILE: 'shared-preview.env' },
    })
    assert.notEqual(relative.status, 0)
    assert.ok(relative.stderr.includes('absolute path'))

    const missing = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: path.join(root, 'missing.env') })
    assert.notEqual(missing.status, 0)
    assert.ok(missing.stderr.includes('existing regular file'))

    const directory = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: root })
    assert.notEqual(directory.status, 0)
    assert.ok(directory.stderr.includes('existing regular file'))

    const link = path.join(root, 'linked.env')
    symlinkSync(valid, link)
    const linked = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: link })
    assert.notEqual(linked.status, 0)
    assert.ok(linked.stderr.includes('symbolic link'))

    for (const mode of [0o640, 0o604, 0o666]) {
      chmodSync(valid, mode)
      const result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: valid })
      assert.notEqual(result.status, 0, `mode ${mode.toString(8)} must be rejected`)
      assert.ok(result.stderr.includes('group or other permissions'))
    }
    chmodSync(valid, 0o600)
  })
})

test('test intent: a config owned by another user is rejected (stat is simulated)', () => {
  withTempDir((root) => {
    const config = writeConfig(root, placeholderConfig())
    const binDir = path.join(root, 'bin')
    mkdirSync(binDir)
    // 只替换属主查询结果，权限查询仍走真实 stat，因此失败必须来自属主校验。
    const shim = path.join(binDir, 'stat')
    writeFileSync(
      shim,
      '#!/usr/bin/env bash\n' +
        'if [ "$1" = "-c" ] && [ "$2" = "%u" ]; then\n' +
        '  printf "%s\\n" "$(( $(id -u) + 1 ))"\n' +
        '  exit 0\n' +
        'fi\n' +
        'exec /usr/bin/stat "$@"\n',
    )
    chmodSync(shim, 0o755)

    const result = sourceDevScript(':', {
      SHARED_PREVIEW_ENV_FILE: config,
      PATH: `${binDir}${path.delimiter}${process.env.PATH}`,
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('owned by the current user'))
    assert.ok(!output(result).includes(VALUE_PREFIX))
  })
})

test('test intent: malformed and unknown keys fail without echoing the line', () => {
  withTempDir((root) => {
    const malformedMarker = 'malformed-marker-value'
    const malformed = writeConfig(root, `# comment\n${malformedMarker}\n`, { name: 'malformed.env' })
    let result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: malformed })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('malformed SHARED_PREVIEW_ENV_FILE line 2'))
    assert.ok(!output(result).includes(malformedMarker))

    const unknownValue = 'unknown-marker-value'
    const unknown = writeConfig(root, `KK_STUDIO_NOT_ALLOWED=${unknownValue}\n`, { name: 'unknown.env' })
    result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: unknown })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('unknown key on SHARED_PREVIEW_ENV_FILE line 1: KK_STUDIO_NOT_ALLOWED'))
    assert.ok(!output(result).includes(unknownValue))
  })
})

test('test intent: the preview marker requires a complete prod configuration', () => {
  // Under the preview marker, missing data or a foreign execution owner fails closed.
  withTempDir((root) => {
    const good = writeConfig(root, placeholderConfig(), { name: 'good.env' })

    let result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: good, ...MARKER })
    assert.equal(result.status, 0, output(result))

    const noFile = sourceDevScript(':', MARKER)
    assert.notEqual(noFile.status, 0)
    assert.ok(noFile.stderr.includes('requires SHARED_PREVIEW_ENV_FILE'))

    const omitted = writeConfig(root, placeholderConfig({ omit: ['KK_STUDIO_STORAGE_S3_SECRET_KEY'] }), {
      name: 'omitted.env',
    })
    result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: omitted, ...MARKER })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('KK_STUDIO_STORAGE_S3_SECRET_KEY'))

    const emptied = writeConfig(root, placeholderConfig({ empty: ['KK_STUDIO_DB_PASSWORD'] }), {
      name: 'emptied.env',
    })
    result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: emptied, ...MARKER })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('KK_STUDIO_DB_PASSWORD'))

    const noDatabase = writeConfig(
      root,
      placeholderConfig().replace(
        `jdbc:postgresql://${VALUE_PREFIX}host/${VALUE_PREFIX}database`,
        `jdbc:postgresql://${VALUE_PREFIX}host`,
      ),
      { name: 'no-database.env' },
    )
    result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: noDatabase, ...MARKER })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('explicit database name'))
    assert.ok(!output(result).includes(VALUE_PREFIX))

    for (const [override, expected] of [
      [{ SPRING_PROFILES_ACTIVE: 'e2e' }, 'SPRING_PROFILES_ACTIVE=prod'],
      [{ SPRING_FLYWAY_ENABLED: 'true' }, 'SPRING_FLYWAY_ENABLED=false'],
      [{ KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED: 'true' }, 'KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false'],
    ]) {
      result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: good, ...MARKER, ...override })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes(expected), expected)
    }

    const { KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED: _removed, ...workersUnset } = MARKER
    result = sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: good, ...workersUnset })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false'))
  })
})

test('test intent: config values are never printed on success or failure', () => {
  withTempDir((root) => {
    const good = writeConfig(root, placeholderConfig(), { name: 'good.env' })
    const scenarios = [
      { SHARED_PREVIEW_ENV_FILE: good },
      { SHARED_PREVIEW_ENV_FILE: good, ...MARKER },
      { SHARED_PREVIEW_ENV_FILE: path.join(root, 'missing.env'), ...MARKER },
      { SHARED_PREVIEW_ENV_FILE: good, SPRING_PROFILES_ACTIVE: 'e2e', SPRING_FLYWAY_ENABLED: 'true' },
    ]
    for (const overrides of scenarios) {
      const combined = output(sourceDevScript(':', overrides))
      assert.ok(!combined.includes(VALUE_PREFIX), JSON.stringify(overrides))
      // 失败场景连键名都不能夹带值，成功场景也不能把值打印出来。
      assert.doesNotMatch(combined, /=fixture-value-/)
    }
    const readable = writeConfig(root, placeholderConfig(), { name: 'readable.env', mode: 0o604 })
    assert.ok(!output(sourceDevScript(':', { SHARED_PREVIEW_ENV_FILE: readable })).includes(VALUE_PREFIX))
  })
})

test('test intent: the tracked template holds names only and points at the owner-only path', () => {
  const template = readText(SHARED_PREVIEW_TEMPLATE)
  for (const key of REQUIRED_KEYS) {
    assert.match(template, new RegExp(`^${key}=$`, 'm'), `${key} must be an empty placeholder`)
  }
  assert.ok(template.includes('.config/kk-studio/shared-preview.env'))
  assert.ok(template.includes('SHARED_PREVIEW_ENV_FILE'))
  assert.doesNotMatch(template, /https?:\/\//)
  assert.doesNotMatch(template, /^\s*(?:export\s+)?[A-Z_]+=\S+/m)
})
