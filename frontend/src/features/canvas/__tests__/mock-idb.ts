/**
 * 测试环境下的 Mock IndexedDB 实现。
 * 仅用于单元测试和自动化集成测试，不进入生产代码包。
 */

interface MockStoreData {
  keyPath: string
  data: Map<string, unknown>
  indexes: Map<string, { keyPath: string | string[] }>
}

interface MockDatabaseData {
  version: number
  stores: Map<string, MockStoreData>
}

export function createMockIDBFactory(options?: { shouldAbortTransaction?: boolean; shouldFailRequest?: boolean }): IDBFactory {
  const databases = new Map<string, MockDatabaseData>()

  return {
    open: (name: string, version = 1) => {
      const openReq: Record<string, unknown> = {
        result: null,
        error: null,
        onsuccess: null,
        onerror: null,
        onupgradeneeded: null,
        transaction: null,
      }

      setTimeout(() => {
        let dbRecord = databases.get(name)
        const oldVersion = dbRecord ? dbRecord.version : 0
        if (!dbRecord) {
          dbRecord = { version, stores: new Map() }
          databases.set(name, dbRecord)
        }

        const createStoreObject = (txStore?: MockStoreData) => ({
          indexNames: {
            contains: (idxName: string) => txStore?.indexes.has(idxName) ?? false,
          } as unknown as DOMStringList,
          createIndex: (indexName: string, keyPath: string | string[]) => {
            txStore?.indexes.set(indexName, { keyPath })
            return {} as IDBIndex
          },
          put: (value: unknown) => {
            const req: Record<string, unknown> = { onsuccess: null, onerror: null, result: undefined }
            if (options?.shouldFailRequest) {
              setTimeout(() => {
                req.error = new Error('Mock request error')
                if (typeof req.onerror === 'function') {
                  ;(req.onerror as (e: unknown) => void)({ target: req })
                }
              }, 0)
              return req as unknown as IDBRequest
            }
            const key = (value as Record<string, unknown>)[txStore?.keyPath ?? 'id'] as string
            txStore?.data.set(key, value)
            setTimeout(() => {
              req.result = key
              if (typeof req.onsuccess === 'function') {
                ;(req.onsuccess as (e: unknown) => void)({ target: req })
              }
            }, 0)
            return req as unknown as IDBRequest
          },
          get: (key: IDBValidKey) => {
            const req: Record<string, unknown> = { onsuccess: null, onerror: null, result: undefined }
            setTimeout(() => {
              req.result = txStore?.data.get(String(key))
              if (typeof req.onsuccess === 'function') {
                ;(req.onsuccess as (e: unknown) => void)({ target: req })
              }
            }, 0)
            return req as unknown as IDBRequest
          },
          delete: (key: IDBValidKey) => {
            const req: Record<string, unknown> = { onsuccess: null, onerror: null, result: undefined }
            txStore?.data.delete(String(key))
            setTimeout(() => {
              if (typeof req.onsuccess === 'function') {
                ;(req.onsuccess as (e: unknown) => void)({ target: req })
              }
            }, 0)
            return req as unknown as IDBRequest
          },
          clear: () => {
            const req: Record<string, unknown> = { onsuccess: null, onerror: null }
            txStore?.data.clear()
            setTimeout(() => {
              if (typeof req.onsuccess === 'function') {
                ;(req.onsuccess as (e: unknown) => void)({ target: req })
              }
            }, 0)
            return req as unknown as IDBRequest
          },
          index: (_indexName: string) => ({
            getAll: (query?: IDBValidKey | IDBKeyRange) => {
              const req: Record<string, unknown> = { onsuccess: null, onerror: null, result: [] }
              setTimeout(() => {
                const all = Array.from(txStore?.data.values() ?? [])
                if (query !== undefined) {
                  const targetVal = String(query)
                  req.result = all.filter((item) => {
                    const record = item as Record<string, unknown>
                    return String(record.canvasId) === targetVal
                      || String(record.sessionKey) === targetVal
                  })
                } else {
                  req.result = all
                }
                if (typeof req.onsuccess === 'function') {
                  ;(req.onsuccess as (e: unknown) => void)({ target: req })
                }
              }, 0)
              return req as unknown as IDBRequest
            },
          }),
          getAll: () => {
            const req: Record<string, unknown> = { onsuccess: null, onerror: null, result: [] }
            setTimeout(() => {
              req.result = Array.from(txStore?.data.values() ?? [])
              if (typeof req.onsuccess === 'function') {
                ;(req.onsuccess as (e: unknown) => void)({ target: req })
              }
            }, 0)
            return req as unknown as IDBRequest
          },
        })

        const createTransactionObject = (storeNames: string | string[], mode?: IDBTransactionMode) => {
          const names = Array.isArray(storeNames) ? storeNames : [storeNames]
          const txStore = dbRecord?.stores.get(names[0] ?? '')

          const tx: Record<string, unknown> = {
            oncomplete: null,
            onerror: null,
            onabort: null,
            error: null,
            mode: mode ?? 'readonly',
            objectStore: (_sName: string) => createStoreObject(txStore),
          }

          setTimeout(() => {
            if (options?.shouldAbortTransaction) {
              tx.error = new Error('Mock transaction abort')
              if (typeof tx.onabort === 'function') {
                ;(tx.onabort as (e: unknown) => void)({ target: tx })
              } else if (typeof tx.onerror === 'function') {
                ;(tx.onerror as (e: unknown) => void)({ target: tx })
              }
            } else if (typeof tx.oncomplete === 'function') {
              ;(tx.oncomplete as (e: unknown) => void)({ target: tx })
            }
          }, 10)

          return tx as unknown as IDBTransaction
        }

        const db: Partial<IDBDatabase> = {
          name,
          version,
          objectStoreNames: {
            contains: (storeName: string) => dbRecord?.stores.has(storeName) ?? false,
          } as unknown as DOMStringList,
          createObjectStore: (storeName: string, storeOptions?: IDBObjectStoreParameters) => {
            const store: MockStoreData = {
              keyPath: (storeOptions?.keyPath as string) ?? 'id',
              data: new Map<string, unknown>(),
              indexes: new Map<string, { keyPath: string | string[] }>(),
            }
            dbRecord?.stores.set(storeName, store)
            return createStoreObject(store) as unknown as IDBObjectStore
          },
          transaction: (storeNames: string | string[], mode?: IDBTransactionMode) => (
            createTransactionObject(storeNames, mode)
          ),
        }

        openReq.result = db
        if (oldVersion < version && typeof openReq.onupgradeneeded === 'function') {
          openReq.transaction = createTransactionObject(['drafts'], 'versionchange')
          ;(openReq.onupgradeneeded as (e: unknown) => void)({
            target: openReq,
            oldVersion,
            newVersion: version,
          })
        }
        if (typeof openReq.onsuccess === 'function') {
          ;(openReq.onsuccess as (e: unknown) => void)({ target: openReq })
        }
      }, 0)

      return openReq as unknown as IDBOpenDBRequest
    },
    deleteDatabase: (_name: string) => {
      const req: Record<string, unknown> = { onsuccess: null, onerror: null }
      setTimeout(() => {
        if (typeof req.onsuccess === 'function') {
          ;(req.onsuccess as (e: unknown) => void)({ target: req })
        }
      }, 0)
      return req as unknown as IDBOpenDBRequest
    },
    cmp: () => 0,
    databases: () => Promise.resolve([]),
  } as unknown as IDBFactory
}
