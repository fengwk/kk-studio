import { describe, expect, it } from 'vitest'
import {
  compareEnvironmentVersions,
  EnvironmentConfigValidationError,
  validateDaemonConfig,
  validateEnvironmentName,
  validateEnvironmentNote,
} from '@/features/environment/environment-config-codec'
import type { DaemonConfigDTO } from '@/shared/api/generated/contracts'

const config = (): DaemonConfigDTO => ({
  studioUrl: 'https://daemon.example.com/',
  bashExecutable: "/bin/汉字 '$`",
  lsp: {
    servers: {
      'server-1': {
        command: ['~/server', "quote' \"$`"],
        extensions: [' .java'],
        rootMarkers: ['pom.xml'],
        firstMatchMarkers: ['.git'],
      },
    },
  },
  proxyUrl: null,
})

describe('validateDaemonConfig', () => {
  it('normalizes origin, proxy, extension case and marker whitespace like the Java codec', () => {
    expect(
      validateDaemonConfig({
        studioUrl: 'HTTPS://Host:8443/',
        bashExecutable: ' bash ',
        lsp: {
          servers: {
            Jdtls: {
              command: [' jdtls '],
              extensions: [' .JAVA '],
              rootMarkers: [' pom.xml '],
              firstMatchMarkers: ['.git'],
            },
          },
        },
        proxyUrl: 'http://127.0.0.1:7890/',
      }),
    ).toEqual({
      studioUrl: 'https://host:8443',
      bashExecutable: ' bash ',
      lsp: {
        servers: {
          Jdtls: {
            command: [' jdtls '],
            extensions: ['.java'],
            rootMarkers: ['pom.xml'],
            firstMatchMarkers: ['.git'],
          },
        },
      },
      proxyUrl: 'http://127.0.0.1:7890',
    })
  })

  it('treats null and blank proxy values as explicit direct and keeps bash optional', () => {
    expect(
      validateDaemonConfig({ studioUrl: 'http://[::1]:8080/', proxyUrl: '' }),
    ).toEqual({
      studioUrl: 'http://[::1]:8080',
      bashExecutable: null,
      lsp: null,
      proxyUrl: null,
    })
    expect(validateDaemonConfig(config()).bashExecutable).toBe("/bin/汉字 '$`")
  })

  it.each([
    'https://127.0.0.1:7890',
    'http://host',
    'http://host:0',
    'http://host:99999',
    'http://user:pass@host:7890',
    'http://host:7890/path',
    'http://host:7890?x',
    'http://host:7890#x',
    'http://host:abc',
    'http://例え.jp:7890',
    'http://host:7890 /',
  ])('rejects proxy %s', (proxyUrl) => {
    expect(() => validateDaemonConfig({ ...config(), proxyUrl })).toThrow(
      /daemonConfig\.proxyUrl/,
    )
  })

  it.each([
    'ftp://host',
    'https://user:pass@host',
    'https://@host',
    'https://host:',
    'https://host:0',
    'https://host /',
    'https://host/path',
    'https://host?x',
    'https://host#x',
    'https://host:99999',
    'https://',
    'https://host\\path',
    'https://host\n',
    'https://host//',
    'https://[::1',
    'https://host:abc',
    'https://例え.jp',
  ])('rejects unsafe studio origin %s', (studioUrl) => {
    expect(() => validateDaemonConfig({ ...config(), studioUrl })).toThrow(
      /daemonConfig\.studioUrl/,
    )
  })

  it('rejects invalid top-level values without echoing them', () => {
    const cases: unknown[] = [
      { ...config(), extra: 'value' },
      { studioUrl: '' },
      { studioUrl: 'https://host', bashExecutable: 'a\nb' },
      { studioUrl: 'https://host', bashExecutable: '  ' },
      { studioUrl: 'https://host', proxyUrl: 'http://a:1\n' },
      null,
      [],
      {},
    ]
    for (const value of cases) {
      expect(() => validateDaemonConfig(value as DaemonConfigDTO)).toThrow(
        /^Invalid /,
      )
    }
  })

  it.each([
    null,
    [],
    {},
    { command: [] },
    { command: [''] },
    { command: [1] },
    { command: ['x'], extensions: 'bad' },
    { command: ['x'], extensions: ['.'] },
    { command: ['x'], extensions: ['java'] },
    { command: ['x'], extensions: ['.a/b'] },
    { command: ['x'], extensions: ['.a', ''] },
    { command: ['x'], extensions: ['.a'], unknown: [] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['..'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['.'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['a//b'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['a:'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['/pom'] },
    { command: ['x'], extensions: ['.a'], firstMatchMarkers: ['C:\\pom'] },
    { command: ['x'], extensions: ['.a'], firstMatchMarkers: 'bad' },
  ])('rejects malformed LSP server %#', (server) => {
    expect(() =>
      validateDaemonConfig({
        ...config(),
        lsp: { servers: { server } } as unknown as DaemonConfigDTO['lsp'],
      }),
    ).toThrow(/^Invalid /)
  })

  it('keeps command required while normalizing omitted or empty optional lists', () => {
    expect(
      validateDaemonConfig({
        ...config(),
        lsp: {
          servers: {
            server: { command: ['x'] },
            empty: {
              command: ['y'],
              extensions: [],
              rootMarkers: [],
              firstMatchMarkers: [],
            },
          },
        },
      }).lsp?.servers,
    ).toEqual({
      server: {
        command: ['x'],
        extensions: [],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
      empty: {
        command: ['y'],
        extensions: [],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
    })
  })

  it.each(['__proto__', 'constructor', 'toString'])(
    'preserves the valid LSP server id %s as an own JSON field',
    (id) => {
      const normalized = validateDaemonConfig({
        studioUrl: 'https://studio.example.com',
        proxyUrl: null,
        lsp: {
          servers: Object.fromEntries([
            [id, { command: ['server'], extensions: ['.ts'] }],
          ]) as DaemonConfigDTO['lsp']['servers'],
        },
      })
      const servers = normalized.lsp!.servers
      expect(Object.getPrototypeOf(servers)).toBeNull()
      expect(Object.hasOwn(servers, id)).toBe(true)
      expect(Object.keys(JSON.parse(JSON.stringify(servers)))).toEqual([id])
      expect(servers[id]).toEqual({
        command: ['server'],
        extensions: ['.ts'],
        rootMarkers: [],
        firstMatchMarkers: [],
      })
    },
  )

  it('rejects empty, invalid and unknown LSP shapes', () => {
    for (const lsp of [
      { servers: {} },
      { servers: [] },
      { servers: { 'bad id': { command: ['x'], extensions: ['.a'] } } },
      { servers: {}, other: true },
    ]) {
      expect(() =>
        validateDaemonConfig({
          ...config(),
          lsp: lsp as unknown as DaemonConfigDTO['lsp'],
        }),
      ).toThrow(/^Invalid /)
    }
  })
})

describe('card identity and note validation', () => {
  it('accepts a canonical name and rejects surrounding whitespace, slash and overlength', () => {
    expect(validateEnvironmentName('local-dev')).toBe('local-dev')
    expect(validateEnvironmentName('a'.repeat(64))).toBe('a'.repeat(64))
    for (const name of ['', '  ', ' a', 'a ', 'a/b', 'a'.repeat(65), 'a\nb']) {
      expect(() => validateEnvironmentName(name)).toThrow(EnvironmentConfigValidationError)
    }
  })

  it('normalizes blank notes to null and preserves 512 characters', () => {
    expect(validateEnvironmentNote('')).toBeNull()
    expect(validateEnvironmentNote('   ')).toBeNull()
    expect(validateEnvironmentNote('  hi  ')).toBe('hi')
    expect(validateEnvironmentNote('x'.repeat(512))).toBe('x'.repeat(512))
    for (const note of ['x'.repeat(513), 'a\nb', '\u0085', 'x\u2028y']) {
      expect(() => validateEnvironmentNote(note)).toThrow(EnvironmentConfigValidationError)
    }
  })

  it('compares canonical decimal versions without Number precision loss', () => {
    expect(compareEnvironmentVersions('9', '10')).toBe(-1)
    expect(compareEnvironmentVersions('10', '10')).toBe(0)
    expect(compareEnvironmentVersions('9007199254740993', '9007199254740992')).toBe(1)
    expect(compareEnvironmentVersions('01', '1')).toBeNull()
    expect(compareEnvironmentVersions('-1', '1')).toBeNull()
  })
})
