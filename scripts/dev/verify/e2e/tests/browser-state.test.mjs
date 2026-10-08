import assert from 'node:assert/strict'
import test from 'node:test'

import {
  matchThreadDraft,
  readThreadDraft,
} from '../lib/browser-state.mjs'

const THREAD_ID = 'thread-1'

function textRecord(overrides = {}) {
  return {
    threadId: THREAD_ID,
    parts: [{ type: 'text', partId: 'part-1', text: 'draft body' }],
    goalText: null,
    appliedStopRequestIds: [],
    ...overrides,
  }
}

test('matches a strict single text part record', () => {
  assert.equal(matchThreadDraft(textRecord(), THREAD_ID, 'draft body'), true)
  // 记录存在但 parts 为空是「无草稿」的合法形态。
  assert.equal(matchThreadDraft(textRecord({ parts: [] }), THREAD_ID, null), true)
  // 缺记录不得冒充已清空且保留元数据的记录。
  assert.equal(matchThreadDraft(null, THREAD_ID, null), false)
})

test('rejects a draft whose text or identity does not match', () => {
  assert.equal(matchThreadDraft(textRecord(), THREAD_ID, 'other body'), false)
  assert.equal(matchThreadDraft(textRecord(), 'thread-2', 'draft body'), false)
  // 期望具体文本时，无记录 / 空 parts 都不成立。
  assert.equal(matchThreadDraft(null, THREAD_ID, 'draft body'), false)
  assert.equal(matchThreadDraft(textRecord({ parts: [] }), THREAD_ID, 'draft body'), false)
})

test('rejects polluted records, extra keys and non-schema parts', () => {
  assert.equal(matchThreadDraft(textRecord({ extra: 1 }), THREAD_ID, 'draft body'), false)
  assert.equal(
    matchThreadDraft(textRecord({ appliedStopRequestIds: undefined }), THREAD_ID, 'draft body'),
    false,
  )
  assert.equal(matchThreadDraft(textRecord({ threadId: undefined }), THREAD_ID, 'draft body'), false)
  assert.equal(matchThreadDraft(textRecord({ parts: 'not-array' }), THREAD_ID, null), false)
  // part 多字段 / 少字段 / 非法类型都不接受。
  assert.equal(
    matchThreadDraft(
      textRecord({ parts: [{ type: 'text', partId: 'p', text: 'draft body', extra: 1 }] }),
      THREAD_ID,
      'draft body',
    ),
    false,
  )
  assert.equal(
    matchThreadDraft(
      textRecord({ parts: [{ type: 'text', text: 'draft body' }] }),
      THREAD_ID,
      'draft body',
    ),
    false,
  )
  assert.equal(
    matchThreadDraft(
      textRecord({ parts: [{ type: 'resource', partId: 'p', text: 'draft body' }] }),
      THREAD_ID,
      'draft body',
    ),
    false,
  )
  assert.equal(
    matchThreadDraft(
      textRecord({ parts: [{ type: 'text', partId: '', text: 'draft body' }] }),
      THREAD_ID,
      'draft body',
    ),
    false,
  )
  // 多个 part 与期望单 text 冲突。
  assert.equal(
    matchThreadDraft(
      textRecord({
        parts: [
          { type: 'text', partId: 'p1', text: 'draft body' },
          { type: 'text', partId: 'p2', text: 'more' },
        ],
      }),
      THREAD_ID,
      'draft body',
    ),
    false,
  )
})

test('rejects malformed goal text and stop receipt identities', () => {
  assert.equal(matchThreadDraft(textRecord({ goalText: 5 }), THREAD_ID, 'draft body'), false)
  assert.equal(
    matchThreadDraft(textRecord({ appliedStopRequestIds: ['a', 'a'] }), THREAD_ID, 'draft body'),
    false,
  )
  assert.equal(
    matchThreadDraft(textRecord({ appliedStopRequestIds: [''] }), THREAD_ID, 'draft body'),
    false,
  )
  assert.equal(
    matchThreadDraft(textRecord({ appliedStopRequestIds: [1] }), THREAD_ID, 'draft body'),
    false,
  )
  // 合法 metadata 保留时仍匹配。
  assert.equal(
    matchThreadDraft(
      textRecord({ goalText: 'goal text', appliedStopRequestIds: ['stop-1'] }),
      THREAD_ID,
      'draft body',
    ),
    true,
  )
})

/** 极简 IndexedDB 替身：只实现只读读取用到的表面。 */
function createFakeIndexedDb({ databases = [], stores = {} } = {}) {
  const state = { databaseNames: [...databases], stores, opened: 0, closed: 0 }
  const indexedDB = {
    databases: async () => state.databaseNames.map((name) => ({ name, version: 1 })),
    open(name) {
      const request = {
        result: null,
        error: null,
        transaction: null,
        onsuccess: null,
        onerror: null,
        onupgradeneeded: null,
      }
      if (!state.databaseNames.includes(name)) {
        // 库不存在：模拟 upgradeneeded + abort，验证只读读取不会建库。
        request.transaction = {
          abort() {
            const index = state.databaseNames.indexOf(name)
            if (index >= 0) state.databaseNames.splice(index, 1)
          },
        }
        state.databaseNames.push(name)
        queueMicrotask(() => {
          request.onupgradeneeded?.()
          request.error = new Error('AbortError')
          request.onerror?.()
        })
        return request
      }
      const storeNames = Object.keys(state.stores[name] ?? {})
      const db = {
        objectStoreNames: { contains: (store) => storeNames.includes(store) },
        transaction(store, mode) {
          assert.equal(mode, 'readonly')
          if (!storeNames.includes(store)) throw new Error('missing store')
          const tx = {
            error: null,
            onerror: null,
            onabort: null,
            oncomplete: null,
            objectStore: () => ({
              get(key) {
                const getRequest = { result: undefined, onsuccess: null, onerror: null }
                queueMicrotask(() => {
                  getRequest.result = state.stores[name]?.[store]?.get(key)
                  getRequest.onsuccess?.()
                  queueMicrotask(() => tx.oncomplete?.())
                })
                return getRequest
              },
            }),
          }
          return tx
        },
        close() {
          state.closed += 1
        },
      }
      state.opened += 1
      queueMicrotask(() => {
        request.result = db
        request.onsuccess?.()
      })
      return request
    },
  }
  return { indexedDB, state }
}

function fakePage() {
  return { evaluate: (fn, arg) => fn(arg) }
}

function withFakeIndexedDb(fake, run) {
  const previous = globalThis.indexedDB
  globalThis.indexedDB = fake.indexedDB
  return Promise.resolve()
    .then(run)
    .finally(() => {
      if (previous === undefined) {
        delete globalThis.indexedDB
      } else {
        globalThis.indexedDB = previous
      }
    })
}

test('reads an existing draft record through a closed readonly transaction', async () => {
  const record = textRecord()
  const { indexedDB, state } = createFakeIndexedDb({
    databases: ['kkstudio.thread-drafts'],
    stores: { 'kkstudio.thread-drafts': { threadDrafts: new Map([[THREAD_ID, record]]) } },
  })
  await withFakeIndexedDb({ indexedDB }, async () => {
    const snapshot = await readThreadDraft(fakePage(), THREAD_ID)
    assert.deepEqual(snapshot, { exists: true, record })
  })
  assert.equal(state.closed, 1, 'readonly connection must be closed after the transaction')
})

test('does not open or create a database that is absent', async () => {
  const { indexedDB, state } = createFakeIndexedDb({ databases: [], stores: {} })
  await withFakeIndexedDb({ indexedDB }, async () => {
    const snapshot = await readThreadDraft(fakePage(), THREAD_ID)
    assert.deepEqual(snapshot, { exists: false, record: null })
  })
  assert.deepEqual(state.databaseNames, [], 'read-only assertion must not create the database')
  assert.equal(state.opened, 0, 'absent database must not be opened')
})

test('unavailable IndexedDB enumeration fails explicitly without a probe or fallback', async () => {
  const record = textRecord()
  const { indexedDB, state } = createFakeIndexedDb({
    databases: ['kkstudio.thread-drafts'],
    stores: { 'kkstudio.thread-drafts': { threadDrafts: new Map([[THREAD_ID, record]]) } },
  })
  delete indexedDB.databases
  await withFakeIndexedDb({ indexedDB }, async () => {
    await assert.rejects(readThreadDraft(fakePage(), THREAD_ID), /databases/)
  })
  assert.equal(state.opened, 0)
})

test('a missing store fails explicitly and still closes the database', async () => {
  const { indexedDB, state } = createFakeIndexedDb({
    databases: ['kkstudio.thread-drafts'], stores: {},
  })
  await withFakeIndexedDb({ indexedDB }, async () => {
    await assert.rejects(readThreadDraft(fakePage(), THREAD_ID), /missing store/)
  })
  assert.equal(state.closed, 1)
})
