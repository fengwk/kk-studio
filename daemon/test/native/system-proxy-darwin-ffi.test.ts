/**
 * Mock-driven CoreFoundation / CFNetwork FFI test suite for the Darwin system proxy bindings.
 *
 * In-memory native handles exercise the production binding, ownership and PAC lifecycle.
 * These mocks are not evidence of macOS ABI compatibility; native runners cover that boundary.
 */

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const CF_RUN_LOOP_RUN_HANDLED_SOURCE = 4

interface MockSource {
  id: bigint
  pacSource: bigint
  targetUrl: bigint
  cb: (client: bigint, proxies: bigint | null, error: bigint | null) => void
  context: bigint
  kind: 'url' | 'script'
  invalidated: boolean
  removed: boolean
}

const h = vi.hoisted(() => {
  const TYPE_STRING = 1n
  const TYPE_NUMBER = 2n
  const TYPE_URL = 3n
  const TYPE_ARRAY = 4n
  const TYPE_DICT = 5n
  const TYPE_ERROR = 6n
  const TYPE_SOURCE = 7n

  let nextPtr = 0x1000n

  const strings = new Map<bigint, string>()
  const numbers = new Map<bigint, number>()
  const urls = new Map<bigint, string>()
  const arrays = new Map<bigint, bigint[]>()
  const dicts = new Map<bigint, Map<bigint, bigint>>()
  const errors = new Map<bigint, number>()
  const types = new Map<bigint, bigint>()

  const symbolPointers = new Map<string, bigint>()
  const symbolValues = new Map<bigint, bigint>()

  const released: bigint[] = []
  const releaseFailures = new Set<bigint>()

  const freed: bigint[] = []
  const registeredCallbacks = new Map<bigint, (client: bigint, proxies: bigint | null, error: bigint | null) => void>()
  const unregisteredTokens: bigint[] = []

  const sourcesAdded: Array<{ loop: bigint; source: bigint; mode: bigint }> = []
  const sourcesRemoved: Array<{ loop: bigint; source: bigint; mode: bigint }> = []
  const sourcesInvalidated: bigint[] = []

  const activeSources = new Map<bigint, MockSource>()

  const failLoadPaths = new Set<string>()
  const failSymbols = new Set<string>()

  let customCopySettings: (() => bigint | null) | null = null
  let customCopyProxies: ((url: bigint, settings: bigint) => bigint | null) | null = null
  let customRunInMode: ((mode: bigint, seconds: number, returnAfterSourceHandled: boolean) => number) | null = null
  let customExecutePacUrl: ((pac: bigint, target: bigint, cb: bigint, context: bigint) => bigint | null) | null = null
  let customExecutePacScript: ((script: bigint, target: bigint, cb: bigint, context: bigint) => bigint | null) | null = null
  let customArrayCount: ((array: bigint) => number) | null = null
  let customArrayAt: ((array: bigint, idx: number) => bigint | null) | null = null
  let customStringBytes: ((str: bigint, buf: Buffer, size: number) => boolean) | null = null
  let customNumberValue: ((num: bigint, type: number, out: Buffer) => boolean) | null = null
  let customUrlCreate: ((bytes: Buffer, length: number) => bigint | null) | null = null
  let customStringCreate: ((bytes: Buffer, length: number) => bigint | null) | null = null
  let customStringMaxSize: ((len: number, enc: number) => number) | null = null
  let customRunLoop: (() => bigint | null) | null = null
  let customUrlString: ((url: bigint) => bigint | null) | null = null

  const allocPtr = (): bigint => {
    const ptr = nextPtr
    nextPtr += 1n
    return ptr
  }

  const reset = (): void => {
    strings.clear()
    numbers.clear()
    urls.clear()
    arrays.clear()
    dicts.clear()
    errors.clear()
    types.clear()
    symbolPointers.clear()
    symbolValues.clear()
    released.length = 0
    releaseFailures.clear()
    freed.length = 0
    registeredCallbacks.clear()
    unregisteredTokens.length = 0
    sourcesAdded.length = 0
    sourcesRemoved.length = 0
    sourcesInvalidated.length = 0
    activeSources.clear()
    failLoadPaths.clear()
    failSymbols.clear()
    customCopySettings = null
    customCopyProxies = null
    customRunInMode = null
    customExecutePacUrl = null
    customExecutePacScript = null
    customArrayCount = null
    customArrayAt = null
    customStringBytes = null
    customNumberValue = null
    customUrlCreate = null
    customStringCreate = null
    customStringMaxSize = null
    customRunLoop = null
    customUrlString = null

    // Pre-populate standard symbols and their corresponding string objects
    const knownSymbols = [
      'kCFRunLoopDefaultMode',
      'kCFProxyTypeKey',
      'kCFProxyTypeNone',
      'kCFProxyTypeHTTP',
      'kCFProxyTypeHTTPS',
      'kCFProxyTypeSOCKS',
      'kCFProxyTypeFTP',
      'kCFProxyTypeAutoConfigurationURL',
      'kCFProxyTypeAutoConfigurationJavaScript',
      'kCFProxyHostNameKey',
      'kCFProxyPortNumberKey',
      'kCFProxyUsernameKey',
      'kCFProxyPasswordKey',
      'kCFProxyAutoConfigurationURLKey',
      'kCFProxyAutoConfigurationJavaScriptKey',
    ]

    for (const name of knownSymbols) {
      const symPtr = allocPtr()
      const valPtr = allocPtr()
      symbolPointers.set(name, symPtr)
      symbolValues.set(symPtr, valPtr)
      strings.set(valPtr, name)
      types.set(valPtr, TYPE_STRING)
    }
  }

  reset()

  return {
    allocPtr,
    reset,
    strings,
    numbers,
    urls,
    arrays,
    dicts,
    errors,
    types,
    symbolPointers,
    symbolValues,
    released,
    releaseFailures,
    freed,
    registeredCallbacks,
    unregisteredTokens,
    sourcesAdded,
    sourcesRemoved,
    sourcesInvalidated,
    activeSources,
    failLoadPaths,
    failSymbols,
    get customCopySettings() {
      return customCopySettings
    },
    set customCopySettings(fn) {
      customCopySettings = fn
    },
    get customCopyProxies() {
      return customCopyProxies
    },
    set customCopyProxies(fn) {
      customCopyProxies = fn
    },
    get customRunInMode() {
      return customRunInMode
    },
    set customRunInMode(fn) {
      customRunInMode = fn
    },
    get customExecutePacUrl() {
      return customExecutePacUrl
    },
    set customExecutePacUrl(fn) {
      customExecutePacUrl = fn
    },
    get customExecutePacScript() {
      return customExecutePacScript
    },
    set customExecutePacScript(fn) {
      customExecutePacScript = fn
    },
    get customArrayCount() {
      return customArrayCount
    },
    set customArrayCount(fn) {
      customArrayCount = fn
    },
    get customArrayAt() {
      return customArrayAt
    },
    set customArrayAt(fn) {
      customArrayAt = fn
    },
    get customStringBytes() {
      return customStringBytes
    },
    set customStringBytes(fn) {
      customStringBytes = fn
    },
    get customNumberValue() {
      return customNumberValue
    },
    set customNumberValue(fn) {
      customNumberValue = fn
    },
    get customUrlCreate() {
      return customUrlCreate
    },
    set customUrlCreate(fn) {
      customUrlCreate = fn
    },
    get customStringCreate() {
      return customStringCreate
    },
    set customStringCreate(fn) {
      customStringCreate = fn
    },
    get customStringMaxSize() {
      return customStringMaxSize
    },
    set customStringMaxSize(fn) {
      customStringMaxSize = fn
    },
    get customRunLoop() {
      return customRunLoop
    },
    set customRunLoop(fn) {
      customRunLoop = fn
    },
    get customUrlString() {
      return customUrlString
    },
    set customUrlString(fn) {
      customUrlString = fn
    },
    TYPE_STRING,
    TYPE_NUMBER,
    TYPE_URL,
    TYPE_ARRAY,
    TYPE_DICT,
    TYPE_ERROR,
    TYPE_SOURCE,
  }
})

const {
  TYPE_STRING,
  TYPE_NUMBER,
  TYPE_URL,
  TYPE_ARRAY,
  TYPE_DICT,
  TYPE_ERROR,
  TYPE_SOURCE,
} = h

vi.mock('koffi', () => {
  const state = h

  const handlers: Record<string, (...args: any[]) => unknown> = {
    CFNetworkCopySystemProxySettings: () => {
      if (state.customCopySettings) {
        return state.customCopySettings()
      }
      const dictPtr = state.allocPtr()
      state.dicts.set(dictPtr, new Map())
      state.types.set(dictPtr, state.TYPE_DICT)
      return dictPtr
    },
    CFNetworkCopyProxiesForURL: (url: bigint, settings: bigint) => {
      if (state.customCopyProxies) {
        return state.customCopyProxies(url, settings)
      }
      const arrPtr = state.allocPtr()
      state.arrays.set(arrPtr, [])
      state.types.set(arrPtr, state.TYPE_ARRAY)
      return arrPtr
    },
    CFNetworkExecuteProxyAutoConfigurationURL: (pac: bigint, target: bigint, cb: bigint, context: bigint) => {
      if (state.customExecutePacUrl) {
        return state.customExecutePacUrl(pac, target, cb, context)
      }
      const sourceId = state.allocPtr()
      const callback = state.registeredCallbacks.get(cb)
      if (!callback) {
        throw new Error('callback not registered')
      }
      state.activeSources.set(sourceId, {
        id: sourceId,
        pacSource: pac,
        targetUrl: target,
        cb: callback,
        context,
        kind: 'url',
        invalidated: false,
        removed: false,
      })
      state.types.set(sourceId, state.TYPE_SOURCE)
      return sourceId
    },
    CFNetworkExecuteProxyAutoConfigurationScript: (script: bigint, target: bigint, cb: bigint, context: bigint) => {
      if (state.customExecutePacScript) {
        return state.customExecutePacScript(script, target, cb, context)
      }
      const sourceId = state.allocPtr()
      const callback = state.registeredCallbacks.get(cb)
      if (!callback) {
        throw new Error('callback not registered')
      }
      state.activeSources.set(sourceId, {
        id: sourceId,
        pacSource: script,
        targetUrl: target,
        cb: callback,
        context,
        kind: 'script',
        invalidated: false,
        removed: false,
      })
      state.types.set(sourceId, state.TYPE_SOURCE)
      return sourceId
    },
    CFRunLoopGetCurrent: () => {
      if (state.customRunLoop) {
        return state.customRunLoop()
      }
      return 0x5000n
    },
    CFRunLoopAddSource: (loop: bigint, source: bigint, mode: bigint) => {
      state.sourcesAdded.push({ loop, source, mode })
    },
    CFRunLoopRemoveSource: (loop: bigint, source: bigint, mode: bigint) => {
      state.sourcesRemoved.push({ loop, source, mode })
      const src = state.activeSources.get(source)
      if (src) {
        src.removed = true
      }
    },
    CFRunLoopSourceInvalidate: (source: bigint) => {
      state.sourcesInvalidated.push(source)
      const src = state.activeSources.get(source)
      if (src) {
        src.invalidated = true
      }
    },
    CFRunLoopRunInMode: (mode: bigint, seconds: number, returnAfterSourceHandled: boolean) => {
      if (state.customRunInMode) {
        return state.customRunInMode(mode, seconds, returnAfterSourceHandled)
      }
      return 0
    },
    CFRelease: (cf: bigint) => {
      state.released.push(cf)
      if (state.releaseFailures.has(cf)) {
        throw new Error('CFRelease simulated failure')
      }
    },
    CFArrayGetCount: (array: bigint) => {
      if (state.customArrayCount) {
        return state.customArrayCount(array)
      }
      return state.arrays.get(array)?.length ?? 0
    },
    CFArrayGetValueAtIndex: (array: bigint, idx: number) => {
      if (state.customArrayAt) {
        return state.customArrayAt(array, idx)
      }
      const items = state.arrays.get(array)
      return items ? (items[Number(idx)] ?? null) : null
    },
    CFDictionaryGetValue: (dictionary: bigint, key: bigint) => {
      const dict = state.dicts.get(dictionary)
      return dict ? (dict.get(key) ?? null) : null
    },
    CFGetTypeID: (cf: bigint) => state.types.get(cf) ?? 0n,
    CFStringGetTypeID: () => state.TYPE_STRING,
    CFNumberGetTypeID: () => state.TYPE_NUMBER,
    CFURLGetTypeID: () => state.TYPE_URL,
    CFURLCreateWithBytes: (_alloc: unknown, bytes: Buffer, length: number) => {
      if (state.customUrlCreate) {
        return state.customUrlCreate(bytes, length)
      }
      const text = bytes.toString('utf8', 0, Number(length))
      const ptr = state.allocPtr()
      state.urls.set(ptr, text)
      state.types.set(ptr, state.TYPE_URL)
      return ptr
    },
    CFStringCreateWithBytes: (_alloc: unknown, bytes: Buffer, length: number) => {
      if (state.customStringCreate) {
        return state.customStringCreate(bytes, length)
      }
      const text = bytes.toString('utf8', 0, Number(length))
      const ptr = state.allocPtr()
      state.strings.set(ptr, text)
      state.types.set(ptr, state.TYPE_STRING)
      return ptr
    },
    CFStringGetLength: (str: bigint) => state.strings.get(str)?.length ?? 0,
    CFStringGetMaximumSizeForEncoding: (length: number, enc: number) => {
      if (state.customStringMaxSize) {
        return state.customStringMaxSize(length, enc)
      }
      return Number(length) * 4 + 1
    },
    CFStringGetCString: (str: bigint, buffer: Buffer, size: number) => {
      if (state.customStringBytes) {
        return state.customStringBytes(str, buffer, size)
      }
      const val = state.strings.get(str)
      if (val === undefined) {
        return false
      }
      const utf = Buffer.from(val, 'utf8')
      if (utf.length >= size) {
        return false
      }
      utf.copy(buffer)
      buffer[utf.length] = 0
      return true
    },
    CFNumberGetValue: (number: bigint, type: number, out: Buffer) => {
      if (state.customNumberValue) {
        return state.customNumberValue(number, type, out)
      }
      const val = state.numbers.get(number)
      if (val === undefined) {
        return false
      }
      out.writeInt32LE(val, 0)
      return true
    },
    CFErrorGetCode: (err: bigint) => state.errors.get(err) ?? -1,
    CFEqual: (a: bigint, b: bigint) => {
      if (a === b) {
        return true
      }
      const strA = state.strings.get(a)
      const strB = state.strings.get(b)
      if (strA !== undefined && strB !== undefined) {
        return strA === strB
      }
      return false
    },
    CFURLGetString: (url: bigint) => {
      if (state.customUrlString) {
        return state.customUrlString(url)
      }
      const text = state.urls.get(url)
      if (text === undefined) {
        return null
      }
      const strPtr = state.allocPtr()
      state.strings.set(strPtr, text)
      state.types.set(strPtr, state.TYPE_STRING)
      return strPtr
    },
  }

  const library = {
    func: (definition: string) => {
      const symbol = definition.match(/(\w+)\s*\(/u)?.[1] ?? ''
      const handler = handlers[symbol]
      if (handler === undefined) {
        throw new Error(`Unmocked FFI function '${symbol}' in ${definition}`)
      }
      return (...args: unknown[]) => handler(...args)
    },
    symbol: (name: string) => {
      if (state.failSymbols.has(name)) {
        throw new Error(`Symbol '${name}' could not be resolved`)
      }
      const symPtr = state.symbolPointers.get(name)
      if (symPtr === undefined) {
        throw new Error(`Unknown symbol '${name}'`)
      }
      return symPtr
    },
  }

  const koffi = {
    load: (path: string) => {
      if (state.failLoadPaths.has(path)) {
        throw new Error(`Library could not be loaded: ${path}`)
      }
      return library
    },
    struct: (name: unknown) => ({ name: typeof name === 'string' ? name : 'anonymous' }),
    proto: (name: unknown) => ({ name: typeof name === 'string' ? name : 'anonymous' }),
    alloc: () => state.allocPtr(),
    encode: () => undefined,
    free: (ptr: bigint) => {
      state.freed.push(ptr)
    },
    decode: (ptr: bigint, type: unknown) => {
      if (type === 'void *') {
        return state.symbolValues.get(ptr) ?? null
      }
      return null
    },
    register: (cb: any) => {
      const token = state.allocPtr()
      state.registeredCallbacks.set(token, cb)
      return token
    },
    unregister: (token: bigint) => {
      state.unregisteredTokens.push(token)
      state.registeredCallbacks.delete(token)
    },
  }

  return { ...koffi, default: koffi }
})

// Import bindings after vi.mock('koffi')
import {
  loadDarwinProxyBindings,
  resetDarwinProxyBindingsForTests,
  type DarwinProxyBindings,
} from '../../src/native/system-proxy-darwin.js'

function getSymbolValue(name: string): bigint {
  const symPtr = h.symbolPointers.get(name)
  if (!symPtr) {
    throw new Error(`symbol ${name} not found`)
  }
  return h.symbolValues.get(symPtr)!
}

function createString(value: string): bigint {
  const ptr = h.allocPtr()
  h.strings.set(ptr, value)
  h.types.set(ptr, TYPE_STRING)
  return ptr
}

function createNumber(value: number): bigint {
  const ptr = h.allocPtr()
  h.numbers.set(ptr, value)
  h.types.set(ptr, TYPE_NUMBER)
  return ptr
}

function createCandidateDict(candidate: {
  type: string
  host?: string
  port?: number
  username?: string
  password?: string
  pacUrl?: string
  pacScript?: string
}): bigint {
  const dictPtr = h.allocPtr()
  const map = new Map<bigint, bigint>()

  map.set(getSymbolValue('kCFProxyTypeKey'), getSymbolValue(candidate.type))

  if (candidate.host !== undefined) {
    map.set(getSymbolValue('kCFProxyHostNameKey'), createString(candidate.host))
  }
  if (candidate.port !== undefined) {
    map.set(getSymbolValue('kCFProxyPortNumberKey'), createNumber(candidate.port))
  }
  if (candidate.username !== undefined) {
    map.set(getSymbolValue('kCFProxyUsernameKey'), createString(candidate.username))
  }
  if (candidate.password !== undefined) {
    map.set(getSymbolValue('kCFProxyPasswordKey'), createString(candidate.password))
  }
  if (candidate.pacUrl !== undefined) {
    const urlPtr = h.allocPtr()
    h.urls.set(urlPtr, candidate.pacUrl)
    h.types.set(urlPtr, TYPE_URL)
    map.set(getSymbolValue('kCFProxyAutoConfigurationURLKey'), urlPtr)
  }
  if (candidate.pacScript !== undefined) {
    map.set(getSymbolValue('kCFProxyAutoConfigurationJavaScriptKey'), createString(candidate.pacScript))
  }

  h.dicts.set(dictPtr, map)
  h.types.set(dictPtr, TYPE_DICT)
  return dictPtr
}

describe('Darwin FFI bindings - copyProxiesForUrl', () => {
  let bindings: DarwinProxyBindings

  beforeEach(() => {
    h.reset()
    resetDarwinProxyBindingsForTests()
    bindings = loadDarwinProxyBindings()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('reads an explicit kCFProxyTypeNone and releases settings, url, and proxies array', () => {
    const settingsPtr = h.allocPtr()
    h.dicts.set(settingsPtr, new Map())
    h.types.set(settingsPtr, TYPE_DICT)
    h.customCopySettings = () => settingsPtr

    const directDict = createCandidateDict({ type: 'kCFProxyTypeNone' })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [directDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    const result = bindings.copyProxiesForUrl('https://example.test/path')

    expect(result).toHaveLength(1)
    expect(result[0]!.type).toBe('none')
    expect(result[0]!.host).toBeNull()
    expect(result[0]!.port).toBeNull()

    // CF Release verified: settings, target url, and proxies array were all released
    expect(h.released).toContain(settingsPtr)
    expect(h.released).toContain(arrayPtr)
    expect(h.released.length).toBe(3) // settings, url, proxies array
  })

  test('reads an HTTP proxy with host, port, credentials, and pac descriptors', () => {
    const httpDict = createCandidateDict({
      type: 'kCFProxyTypeHTTP',
      host: 'proxy.internal',
      port: 8080,
      username: 'user1',
      password: 'secretPassword',
    })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [httpDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    const result = bindings.copyProxiesForUrl('http://example.test/')
    expect(result).toHaveLength(1)
    expect(result[0]!.type).toBe('http')
    expect(result[0]!.host).toBe('proxy.internal')
    expect(result[0]!.port).toBe(8080)
    expect(result[0]!.username).toBe('user1')
    expect(result[0]!.password).toBe('secretPassword')
  })

  test('reads multiple candidate types: https, socks, ftp, pac-url, pac-script, and other', () => {
    const dicts = [
      createCandidateDict({ type: 'kCFProxyTypeHTTPS', host: 'sec.proxy', port: 8443 }),
      createCandidateDict({ type: 'kCFProxyTypeSOCKS', host: 'socks.proxy', port: 1080 }),
      createCandidateDict({ type: 'kCFProxyTypeFTP', host: 'ftp.proxy', port: 21 }),
      createCandidateDict({ type: 'kCFProxyTypeAutoConfigurationURL', pacUrl: 'http://pac.local/proxy.pac' }),
      createCandidateDict({ type: 'kCFProxyTypeAutoConfigurationJavaScript', pacScript: 'function FindProxy() {}' }),
    ]
    // Add an unknown/other type dict
    const otherDict = h.allocPtr()
    const otherMap = new Map<bigint, bigint>()
    const unknownTypeStr = createString('kCFProxyTypeCustomVendor')
    otherMap.set(getSymbolValue('kCFProxyTypeKey'), unknownTypeStr)
    h.dicts.set(otherDict, otherMap)
    h.types.set(otherDict, TYPE_DICT)
    dicts.push(otherDict)

    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, dicts)
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    const result = bindings.copyProxiesForUrl('https://multi.test/')
    expect(result).toHaveLength(6)
    expect(result[0]!.type).toBe('https')
    expect(result[1]!.type).toBe('socks')
    expect(result[2]!.type).toBe('ftp')
    expect(result[3]!.type).toBe('pac-url')
    expect(result[3]!.pacUrl).toBe('http://pac.local/proxy.pac')
    expect(result[4]!.type).toBe('pac-script')
    expect(result[4]!.pacScript).toBe('function FindProxy() {}')
    expect(result[5]!.type).toBe('other')
  })

  test('fails closed when CFNetworkCopySystemProxySettings returns null', () => {
    h.customCopySettings = () => null
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/unavailable/)
  })

  test('fails closed when CFNetworkCopyProxiesForURL returns null', () => {
    h.customCopyProxies = () => null
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/result could not be read/)
  })

  test('fails closed when array count is invalid', () => {
    h.customArrayCount = () => -1
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/result could not be read/)
  })

  test('fails closed when array item is null', () => {
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [0x0n])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr
    h.customArrayAt = () => null

    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/result could not be read/)
  })

  test('fails closed when dictionary has no kCFProxyTypeKey', () => {
    const malformedDict = h.allocPtr()
    h.dicts.set(malformedDict, new Map()) // empty dict
    h.types.set(malformedDict, TYPE_DICT)

    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [malformedDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when CFStringGetCString fails for a string value', () => {
    const dict = createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'bad.host', port: 8080 })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [dict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    h.customStringBytes = () => false
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when CFNumberGetValue fails for port number', () => {
    const dict = createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'host.ok', port: 8080 })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [dict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    h.customNumberValue = () => false
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed and redacts sensitive URL when CFRelease fails during cleanup', () => {
    const secretUrl = 'https://sensitive-user:super-secret-token@internal.corp/private-api'
    const settingsPtr = h.allocPtr()
    h.dicts.set(settingsPtr, new Map())
    h.types.set(settingsPtr, TYPE_DICT)
    h.customCopySettings = () => settingsPtr

    // Inject failure when releasing settings
    h.releaseFailures.add(settingsPtr)

    try {
      bindings.copyProxiesForUrl(secretUrl)
      expect.unreachable('should have thrown on CFRelease failure')
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      expect(message).toContain('handle could not be released')
      expect(message).not.toContain('super-secret-token')
      expect(message).not.toContain('sensitive-user')
      expect(message).not.toContain('internal.corp')
    }
  })

  test('fails closed when symbol resolution returns null pointer', () => {
    resetDarwinProxyBindingsForTests()
    // Delete kCFProxyTypeKey symbol value so decode returns null
    const symPtr = h.symbolPointers.get('kCFProxyTypeKey')!
    h.symbolValues.delete(symPtr)

    const dict = createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'proxy.com', port: 80 })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [dict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    const b = loadDarwinProxyBindings()
    expect(() => b.copyProxiesForUrl('https://example.test/')).toThrow(/unavailable/)
  })

  test('fails closed when CoreFoundation framework cannot be loaded', () => {
    resetDarwinProxyBindingsForTests()
    h.failLoadPaths.add('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    expect(() => loadDarwinProxyBindings()).toThrow(/could not be loaded/)
  })

  test('fails closed when CFNetwork framework cannot be loaded', () => {
    resetDarwinProxyBindingsForTests()
    h.failLoadPaths.add('/System/Library/Frameworks/CFNetwork.framework/CFNetwork')
    expect(() => loadDarwinProxyBindings()).toThrow(/could not be loaded/)
  })

  test('fails closed when defaultMode symbol returns null during executePac', async () => {
    // Delete kCFRunLoopDefaultMode symbol value so decode returns null
    const modeSymPtr = h.symbolPointers.get('kCFRunLoopDefaultMode')!
    h.symbolValues.delete(modeSymPtr)

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )
    expect(outcome).toEqual({ kind: 'error', code: -1 })
  })

  test('fails closed when URL creation returns null', () => {
    h.customUrlCreate = () => null
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when string maximum size returns negative', () => {
    const dict = createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'proxy.com', port: 80 })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [dict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    h.customStringMaxSize = () => -1
    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when dictionary field has wrong type (e.g. host is a number instead of string)', () => {
    const malformedDict = h.allocPtr()
    const map = new Map<bigint, bigint>()
    map.set(getSymbolValue('kCFProxyTypeKey'), getSymbolValue('kCFProxyTypeHTTP'))
    // Put a number into HostNameKey instead of a string
    map.set(getSymbolValue('kCFProxyHostNameKey'), createNumber(12345))
    h.dicts.set(malformedDict, map)
    h.types.set(malformedDict, TYPE_DICT)

    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [malformedDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when port in dictionary is a string instead of number', () => {
    const malformedDict = h.allocPtr()
    const map = new Map<bigint, bigint>()
    map.set(getSymbolValue('kCFProxyTypeKey'), getSymbolValue('kCFProxyTypeHTTP'))
    map.set(getSymbolValue('kCFProxyHostNameKey'), createString('proxy.example'))
    // Put a string into PortNumberKey instead of a number
    map.set(getSymbolValue('kCFProxyPortNumberKey'), createString('8080'))
    h.dicts.set(malformedDict, map)
    h.types.set(malformedDict, TYPE_DICT)

    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [malformedDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })

  test('fails closed when pacUrl in dictionary has wrong type (string instead of CFURL)', () => {
    const malformedDict = h.allocPtr()
    const map = new Map<bigint, bigint>()
    map.set(getSymbolValue('kCFProxyTypeKey'), getSymbolValue('kCFProxyTypeAutoConfigurationURL'))
    // Put a string instead of a CFURL
    map.set(getSymbolValue('kCFProxyAutoConfigurationURLKey'), createString('http://bad.pac'))
    h.dicts.set(malformedDict, map)
    h.types.set(malformedDict, TYPE_DICT)

    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [malformedDict])
    h.types.set(arrayPtr, TYPE_ARRAY)
    h.customCopyProxies = () => arrayPtr

    expect(() => bindings.copyProxiesForUrl('https://example.test/')).toThrow(/malformed/)
  })
})

describe('Darwin FFI bindings - executePac', () => {
  let bindings: DarwinProxyBindings

  beforeEach(() => {
    h.reset()
    resetDarwinProxyBindingsForTests()
    bindings = loadDarwinProxyBindings()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('executes PAC from URL, receives proxies array from callback, and cleans up all CF resources', async () => {
    const httpDict = createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'pac-proxy.local', port: 3128 })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [httpDict])
    h.types.set(arrayPtr, TYPE_ARRAY)

    h.customRunInMode = (_mode, _seconds, _returnAfterSourceHandled) => {
      for (const src of h.activeSources.values()) {
        if (!src.invalidated) {
          src.cb(0x999n, arrayPtr, null)
          return CF_RUN_LOOP_RUN_HANDLED_SOURCE
        }
      }
      return 0
    }

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome.kind).toBe('proxies')
    if (outcome.kind === 'proxies') {
      expect(outcome.proxies).toHaveLength(1)
      expect(outcome.proxies[0]!.type).toBe('http')
      expect(outcome.proxies[0]!.host).toBe('pac-proxy.local')
      expect(outcome.proxies[0]!.port).toBe(3128)
    }

    // Verify all resources were released:
    // 1. source was invalidated
    expect(h.sourcesInvalidated.length).toBe(1)
    // 2. source was removed from run loop
    expect(h.sourcesRemoved.length).toBe(1)
    // 3. source was released
    const source = [...h.activeSources.values()][0]!
    expect(h.released).toContain(source.id)
    // 4. pac and target URL objects were released
    expect(h.released).toContain(source.pacSource)
    expect(h.released).toContain(source.targetUrl)
    // 5. context was freed
    expect(h.freed).toContain(source.context)
    // 6. callback was unregistered
    expect(h.unregisteredTokens.length).toBe(1)
  })

  test('executes inline PAC script via CFNetworkExecuteProxyAutoConfigurationScript', async () => {
    const directDict = createCandidateDict({ type: 'kCFProxyTypeNone' })
    const arrayPtr = h.allocPtr()
    h.arrays.set(arrayPtr, [directDict])
    h.types.set(arrayPtr, TYPE_ARRAY)

    let usedScriptFunction = false
    h.customExecutePacScript = (script, target, cb, context) => {
      usedScriptFunction = true
      const sourceId = h.allocPtr()
      const callback = h.registeredCallbacks.get(cb)!
      h.activeSources.set(sourceId, {
        id: sourceId,
        pacSource: script,
        targetUrl: target,
        cb: callback,
        context,
        kind: 'script',
        invalidated: false,
        removed: false,
      })
      h.types.set(sourceId, TYPE_SOURCE)
      return sourceId
    }

    h.customRunInMode = () => {
      for (const src of h.activeSources.values()) {
        if (!src.invalidated && src.kind === 'script') {
          src.cb(0x999n, arrayPtr, null)
          return CF_RUN_LOOP_RUN_HANDLED_SOURCE
        }
      }
      return 0
    }

    const outcome = await bindings.executePac(
      { kind: 'script', value: 'function FindProxyForURL() { return "DIRECT"; }' },
      'https://target.local/',
      () => false,
    )

    expect(usedScriptFunction).toBe(true)
    expect(outcome.kind).toBe('proxies')
    if (outcome.kind === 'proxies') {
      expect(outcome.proxies).toHaveLength(1)
      expect(outcome.proxies[0]!.type).toBe('none')
    }
  })

  test('reports CFError code when PAC callback receives an error', async () => {
    const errPtr = h.allocPtr()
    h.errors.set(errPtr, 403)
    h.types.set(errPtr, TYPE_ERROR)

    h.customRunInMode = () => {
      for (const src of h.activeSources.values()) {
        if (!src.invalidated) {
          src.cb(0x999n, null, errPtr)
          return CF_RUN_LOOP_RUN_HANDLED_SOURCE
        }
      }
      return 0
    }

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome).toEqual({ kind: 'error', code: 403 })
  })

  test('reports error code -1 when PAC callback receives null proxies and null error', async () => {
    h.customRunInMode = () => {
      for (const src of h.activeSources.values()) {
        if (!src.invalidated) {
          src.cb(0x999n, null, null)
          return CF_RUN_LOOP_RUN_HANDLED_SOURCE
        }
      }
      return 0
    }

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome).toEqual({ kind: 'error', code: -1 })
  })

  test('cancels cleanly during the RunLoop poll and cleans up all allocated handles', async () => {
    let loopIterations = 0
    let cancelled = false

    h.customRunInMode = () => {
      loopIterations += 1
      if (loopIterations >= 2) {
        cancelled = true
      }
      return 0
    }

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => cancelled,
    )

    expect(outcome.kind).toBe('cancelled')
    expect(h.sourcesInvalidated.length).toBe(1)
    expect(h.sourcesRemoved.length).toBe(1)
    expect(h.unregisteredTokens.length).toBe(1)
  })

  test('yields to an external abort event while the native run loop is pending', async () => {
    const controller = new AbortController()
    setImmediate(() => controller.abort())
    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => controller.signal.aborted,
    )
    expect(outcome.kind).toBe('cancelled')
    expect(h.sourcesInvalidated).toHaveLength(1)
    expect(h.freed).toHaveLength(1)
    expect(h.unregisteredTokens).toHaveLength(1)
  })

  test('times out when PAC budget is exceeded and cleans up all allocated handles', async () => {
    let now = 10_000
    vi.spyOn(Date, 'now').mockImplementation(() => {
      now += 4_000 // increments by 4s each call: 1st (deadline) 14000, 2nd 18000 (> 24000), 3rd 22000, 4th 26000
      return now
    })

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome.kind).toBe('timeout')
    expect(h.sourcesInvalidated.length).toBe(1)
    expect(h.sourcesRemoved.length).toBe(1)
    expect(h.unregisteredTokens.length).toBe(1)
  })

  test('isolates concurrent PAC URL and PAC Script queries and keeps their resources independent', async () => {
    const arrayUrl = h.allocPtr()
    h.arrays.set(arrayUrl, [createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'proxy1', port: 8080 })])
    h.types.set(arrayUrl, TYPE_ARRAY)

    const arrayScript = h.allocPtr()
    h.arrays.set(arrayScript, [createCandidateDict({ type: 'kCFProxyTypeHTTP', host: 'proxy2', port: 9090 })])
    h.types.set(arrayScript, TYPE_ARRAY)

    let step = 0
    h.customRunInMode = () => {
      step += 1
      for (const src of h.activeSources.values()) {
        if (!src.invalidated) {
          if (src.kind === 'url' && step === 1) {
            src.cb(0x1n, arrayUrl, null)
            return CF_RUN_LOOP_RUN_HANDLED_SOURCE
          }
          if (src.kind === 'script' && step === 2) {
            src.cb(0x2n, arrayScript, null)
            return CF_RUN_LOOP_RUN_HANDLED_SOURCE
          }
        }
      }
      return 0
    }

    const [resUrl, resScript] = await Promise.all([
      bindings.executePac({ kind: 'url', value: 'http://pac1.local/' }, 'https://target1.local/', () => false),
      bindings.executePac({ kind: 'script', value: 'script2' }, 'https://target2.local/', () => false),
    ])

    expect(resUrl.kind).toBe('proxies')
    if (resUrl.kind === 'proxies') {
      expect(resUrl.proxies[0]!.host).toBe('proxy1')
    }

    expect(resScript.kind).toBe('proxies')
    if (resScript.kind === 'proxies') {
      expect(resScript.proxies[0]!.host).toBe('proxy2')
    }

    // Both queries performed full cleanup independently
    expect(h.sourcesInvalidated.length).toBe(2)
    expect(h.sourcesRemoved.length).toBe(2)
    expect(h.unregisteredTokens.length).toBe(2)
    expect(h.freed.length).toBe(2)
  })

  test('reports error when native executePacUrl returns null source', async () => {
    h.customExecutePacUrl = () => null

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome).toEqual({ kind: 'error', code: -1 })
  })

  test('reports error when script creation fails for inline PAC script', async () => {
    h.customStringCreate = () => null

    const outcome = await bindings.executePac(
      { kind: 'script', value: 'bad-script' },
      'https://target.local/',
      () => false,
    )

    expect(outcome).toEqual({ kind: 'error', code: -1 })
  })

  test('reports error when cleanup fails during executePac teardown', async () => {
    h.customRunInMode = () => {
      for (const src of h.activeSources.values()) {
        if (!src.invalidated) {
          src.cb(0x1n, null, null)
          return CF_RUN_LOOP_RUN_HANDLED_SOURCE
        }
      }
      return 0
    }

    // Cause release of a source to throw
    h.customExecutePacUrl = (pac, target, cb, context) => {
      const sourceId = h.allocPtr()
      h.releaseFailures.add(sourceId) // will throw on CFRelease
      const callback = h.registeredCallbacks.get(cb)!
      h.activeSources.set(sourceId, {
        id: sourceId,
        pacSource: pac,
        targetUrl: target,
        cb: callback,
        context,
        kind: 'url',
        invalidated: false,
        removed: false,
      })
      h.types.set(sourceId, TYPE_SOURCE)
      return sourceId
    }

    const outcome = await bindings.executePac(
      { kind: 'url', value: 'http://pac.local/proxy.pac' },
      'https://target.local/',
      () => false,
    )

    expect(outcome).toEqual({ kind: 'error', code: -1 })
  })
})
