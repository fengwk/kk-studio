import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const CHECK_SCRIPT = fileURLToPath(new URL('../check.mjs', import.meta.url))

function createRoot(files) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-architecture-rules-'))
  for (const [relativePath, content] of Object.entries(files)) {
    const absolutePath = path.join(root, relativePath)
    mkdirSync(path.dirname(absolutePath), { recursive: true })
    writeFileSync(absolutePath, content)
  }
  return root
}

function diagnostics(root) {
  const result = spawnSync(process.execPath, [CHECK_SCRIPT, '--root', root], {
    encoding: 'utf8',
  })
  assert.equal(result.error, undefined)
  return result.stderr
    .split('\n')
    .filter((line) => line.startsWith('- '))
    .map((line) => line.slice(2))
}

const schema = `
create table harness_thread (id uuid primary key);
create table durable_work (id uuid primary key);
create table canvas_node (id uuid primary key);
create table chat (id uuid primary key);
`

test('harness access guard rejects internal packages and restricted store ports', () => {
  // Test intent: business packages must not reach runtime internals or storage ports.
  const root = createRoot({
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/definition/HarnessLeak.java':
      `package fun.fengwk.kkstudio.platform.catalog.definition;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery;
class HarnessLeak {}
`,
  })
  try {
    const messages = diagnostics(root)
    assert.equal(
      messages.some((message) => message.includes('HarnessLeak.java:2: runtime internals')),
      true,
      'runtime internals must be rejected',
    )
    assert.equal(
      messages.some((message) =>
        message.includes('HarnessLeak.java:3: only platform engine adapters may use harness.spi.store'),
      ),
      true,
      'spi.store must be rejected in a business package',
    )
    assert.equal(
      messages.some((message) =>
        message.includes('HarnessLeak.java:4: Builtin storage port is limited'),
      ),
      true,
      'BuiltinStore must be limited to platform.harness.builtin',
    )
    assert.equal(
      messages.some((message) =>
        message.includes('HarnessLeak.java:5: Builtin storage contracts are limited'),
      ),
      true,
      'builtin.port.store value contracts must stay in platform.harness',
    )
    assert.equal(
      messages.some((message) =>
        message.includes('HarnessLeak.java:6: Builtin Driver internals are limited'),
      ),
      true,
      'builtin query internals must be rejected',
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('harness access guard accepts public API and declared engine adapters', () => {
  // Test intent: the public Harness surface and the two storage adapter layers remain usable.
  const root = createRoot({
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/PublicApiProbe.java':
      `package fun.fengwk.kkstudio.platform.catalog;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
class PublicApiProbe { ThreadState state; }
`,
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/store/StoreProbe.java':
      `package fun.fengwk.kkstudio.platform.harness.store;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
class StoreProbe { HarnessStore store; }
`,
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/builtin/store/BuiltinProbe.java':
      `package fun.fengwk.kkstudio.platform.harness.builtin.store;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
class BuiltinProbe { BuiltinStore store; }
`,
  })
  try {
    const messages = diagnostics(root)
    assert.deepEqual(
      messages.filter((message) => message.includes('Harness boundary') || message.includes('harness.spi.store') || message.includes('Builtin')),
      [],
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('table ownership guard rejects cross-module SQL and accepts the owner table', () => {
  // Test intent: SQL is checked by the single table prefix map, not by file-local allowlists.
  const root = createRoot({
    'schema/src/main/resources/db/migration/V1__schema.sql': schema,
    'apps/chat/src/main/java/fun/fengwk/kkstudio/chat/ChatLeak.java':
      `package fun.fengwk.kkstudio.chat;
class ChatLeak {
  static final String SQL = """
      select * from harness_thread
      """;
}
`,
    'apps/chat/src/main/java/fun/fengwk/kkstudio/chat/ChatOwnTable.java':
      `package fun.fengwk.kkstudio.chat;
class ChatOwnTable {
  static final String SQL = """
      select * from chat
      """;
}
`,
  })
  try {
    const messages = diagnostics(root)
    assert.equal(
      messages.some((message) =>
        message.includes('ChatLeak.java:4: harness_thread is owned by'),
      ),
      true,
    )
    assert.equal(
      messages.some((message) => message.includes('ChatOwnTable.java')),
      false,
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('table ownership guard allows only work-dependent read-only durable_work access', () => {
  // Test intent: durable_work is the documented read-only exception for modules depending on work.
  const root = createRoot({
    'schema/src/main/resources/db/migration/V1__schema.sql': schema,
    'apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasWorkRead.java':
      `package fun.fengwk.kkstudio.canvas;
class CanvasWorkRead {
  static final String SQL = """
      select 1 from durable_work w join canvas_node n on n.id = w.target_id
      """;
}
`,
    'apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasWorkWrite.java':
      `package fun.fengwk.kkstudio.canvas;
class CanvasWorkWrite {
  static final String SQL = """
      update durable_work set available_at = statement_timestamp()
      """;
}
`,
    'apps/chat/src/main/java/fun/fengwk/kkstudio/chat/ChatWorkRead.java':
      `package fun.fengwk.kkstudio.chat;
class ChatWorkRead {
  static final String SQL = """
      select 1 from durable_work
      """;
}
`,
  })
  try {
    const messages = diagnostics(root)
    assert.equal(
      messages.some((message) => message.includes('CanvasWorkRead.java')),
      false,
    )
    assert.equal(
      messages.some((message) =>
        message.includes('CanvasWorkWrite.java:4: durable_work is owned by'),
      ),
      true,
    )
    assert.equal(
      messages.some((message) => message.includes('ChatWorkRead.java:4: durable_work is owned by')),
      true,
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('MCP client boundary allows logging annotation but rejects unrelated dependencies', () => {
  // Test intent: logging failures is observable without opening the client boundary.
  const root = createRoot({
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/BoundaryProbe.java':
      `package fun.fengwk.kkstudio.platform.catalog.mcp.client;
import lombok.extern.slf4j.Slf4j;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
class BoundaryProbe {}
`,
  })
  try {
    const messages = diagnostics(root).filter((message) => message.includes('MCP client imports'))
    assert.equal(messages.length, 1)
    assert.match(messages[0], /BoundaryProbe\.java:3:.*platform\.settings\.SystemSettings/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('ported architecture guards reject stale provider enums and Redis configuration', () => {
  // Test intent: removed handwritten tests keep their behavioral constraints in the repository check.
  const root = createRoot({
    'share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/AgentProviderType.java':
      'package fun.fengwk.kkstudio.share.ai.catalog;\nclass AgentProviderType {}\n',
    'web/src/main/resources/application.yml': 'redis:\n  url: redis://localhost:6379\n',
  })
  try {
    const messages = diagnostics(root)
    assert.equal(
      messages.some((message) => message.includes('share must not reintroduce AgentProviderType')),
      true,
    )
    assert.equal(
      messages.some((message) => message.includes('application.yml:2: Redis configuration must be absent')),
      true,
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
