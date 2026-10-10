import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { LspServerConfig } from '../contracts.js'
import { LspError } from './lsp-error.js'

export interface ResolvedServerConfig {
  id: string
  command: string[]
  extensions: string[]
  rootMarkers: string[]
  firstMatchMarkers: string[]
}

export interface LspSupport {
  language: string | null
  available: boolean
  supported: boolean
}

export interface ExecutableResolverPlatform {
  windows?: boolean
  pathVariable?: string | undefined
  pathext?: string | undefined
  home?: string | undefined
}

export class ExecutableResolver {
  static resolve(command: string, platform: ExecutableResolverPlatform = {}): string | undefined {
    const isWin = platform.windows ?? (process.platform === 'win32')
    const pathVar = platform.pathVariable !== undefined ? platform.pathVariable : process.env['PATH']
    const pathext = platform.pathext !== undefined ? platform.pathext : process.env['PATHEXT']
    const home = platform.home !== undefined ? platform.home : os.homedir()

    const expanded = this.expandHome(command, home)
    if (!expanded.includes('/') && !expanded.includes('\\')) {
      return this.resolveOnPath(expanded, isWin, pathVar, pathext, home)
    }

    if (!path.isAbsolute(expanded)) {
      return undefined
    }

    return this.isRunnable(expanded, isWin) ? path.normalize(expanded) : undefined
  }

  static expandHome(value: string, home: string): string {
    if (value === '~') {
      return home
    }
    if (value.startsWith('~/') || value.startsWith('~\\')) {
      return path.join(home, value.slice(2))
    }
    if (value.startsWith('$HOME/') || value.startsWith('$HOME\\')) {
      return path.join(home, value.slice(6))
    }
    if (value.startsWith('${HOME}/') || value.startsWith('${HOME}\\')) {
      return path.join(home, value.slice(8))
    }
    return value
  }

  private static resolveOnPath(
    command: string,
    windows: boolean,
    pathVariable: string | undefined,
    pathext: string | undefined,
    home: string,
  ): string | undefined {
    if (!pathVariable || pathVariable.trim().length === 0) {
      return undefined
    }
    const delimiter = windows ? ';' : ':'
    const elements = pathVariable.split(delimiter)
    for (const element of elements) {
      if (!element || element.trim().length === 0) {
        continue
      }
      let directory: string
      try {
        directory = path.resolve(this.expandHome(element.trim(), home))
      } catch {
        continue
      }
      const candidate = windows
        ? this.resolveWindows(directory, command, pathext)
        : this.resolvePosix(directory, command)
      if (candidate) {
        return candidate
      }
    }
    return undefined
  }

  private static resolvePosix(directory: string, command: string): string | undefined {
    const candidate = path.join(directory, command)
    try {
      const stat = fs.statSync(candidate)
      if (stat.isFile()) {
        fs.accessSync(candidate, fs.constants.X_OK)
        return path.normalize(candidate)
      }
    } catch {
      return undefined
    }
    return undefined
  }

  private static resolveWindows(
    directory: string,
    command: string,
    pathext: string | undefined,
  ): string | undefined {
    const suffixes = this.windowsExecutableSuffixes(pathext)
    if (this.hasRecognizedSuffix(command, suffixes)) {
      const candidate = path.join(directory, command)
      try {
        if (fs.statSync(candidate).isFile()) {
          return path.normalize(candidate)
        }
      } catch {
        return undefined
      }
      return undefined
    }
    for (const suffix of suffixes) {
      const candidate = path.join(directory, command + suffix)
      try {
        if (fs.statSync(candidate).isFile()) {
          return path.normalize(candidate)
        }
      } catch {
        continue
      }
    }
    return undefined
  }

  static windowsExecutableSuffixes(pathext: string | undefined): string[] {
    const value = pathext && pathext.trim().length > 0 ? pathext : '.EXE;.CMD;.BAT;.COM'
    const suffixes: string[] = []
    for (const suffix of value.split(';')) {
      const trimmed = suffix.trim()
      if (trimmed.length > 0) {
        suffixes.push(trimmed.startsWith('.') ? trimmed.toLowerCase() : '.' + trimmed.toLowerCase())
      }
    }
    return suffixes
  }

  private static hasRecognizedSuffix(command: string, suffixes: string[]): boolean {
    const lower = command.toLowerCase()
    return suffixes.some((suffix) => lower.endsWith(suffix))
  }

  private static isRunnable(candidate: string, windows: boolean): boolean {
    try {
      const stat = fs.statSync(candidate)
      if (!stat.isFile()) {
        return false
      }
      if (windows) {
        return true
      }
      fs.accessSync(candidate, fs.constants.X_OK)
      return true
    } catch {
      return false
    }
  }
}

export class LspDiscovery {
  private readonly _servers: ResolvedServerConfig[]
  private readonly executables = new Map<string, string | undefined>()

  constructor(servers: ResolvedServerConfig[]) {
    this._servers = [...servers]
  }

  static empty(): LspDiscovery {
    return new LspDiscovery([])
  }

  static of(servers: ResolvedServerConfig[]): LspDiscovery {
    const seen = new Set<string>()
    for (const server of servers) {
      if (seen.has(server.id)) {
        throw new LspError('duplicate lsp server id: ' + server.id)
      }
      seen.add(server.id)
    }
    return new LspDiscovery(servers)
  }

  static fromConfiguration(lspConfig: Record<string, LspServerConfig> | undefined): LspDiscovery {
    if (!lspConfig) {
      return this.empty()
    }
    const servers: ResolvedServerConfig[] = []
    for (const [id, config] of Object.entries(lspConfig)) {
      servers.push({
        id,
        command: [...config.command],
        extensions: config.extensions.map((ext) => ext.toLowerCase()),
        rootMarkers: [...config.rootMarkers],
        firstMatchMarkers: [...config.firstMatchMarkers],
      })
    }
    return this.of(servers)
  }

  servers(): ResolvedServerConfig[] {
    return [...this._servers]
  }

  server(filePath: string): ResolvedServerConfig | undefined {
    const ext = this.extension(filePath)
    if (!ext) {
      return undefined
    }
    return this._servers.find((s) => s.extensions.includes(ext.toLowerCase()))
  }

  requireServer(filePath: string): ResolvedServerConfig {
    const matched = this.server(filePath)
    if (!matched) {
      throw new LspError(
        `No LSP server configured for ${filePath}. Add the server to lsp.servers in the daemon configuration.`,
      )
    }
    return matched
  }

  executable(server: ResolvedServerConfig): string | undefined {
    const first = server.command[0]
    if (!first) {
      return undefined
    }
    if (this.executables.has(first)) {
      return this.executables.get(first)
    }
    const resolved = ExecutableResolver.resolve(first)
    this.executables.set(first, resolved)
    return resolved
  }

  requireExecutable(server: ResolvedServerConfig): string {
    const resolved = this.executable(server)
    if (!resolved) {
      const first = server.command[0] ?? ''
      throw new LspError(
        `LSP server '${server.id}' is not installed. Command '${first}' must be available on PATH or be an absolute executable path.`,
      )
    }
    return resolved
  }

  support(filePath: string): LspSupport {
    const matched = this.server(filePath)
    if (!matched) {
      return { language: null, available: false, supported: false }
    }
    return {
      language: matched.id,
      available: this.executable(matched) !== undefined,
      supported: true,
    }
  }

  workspaceRoot(server: ResolvedServerConfig, filePath: string): string {
    const target = path.resolve(filePath)
    const directory = path.dirname(target)
    if (!directory || directory === target) {
      return target
    }
    const gitRoot = this.findGitRoot(directory)
    const limit = gitRoot ? path.dirname(gitRoot) : null

    let buildRoot: string | null = null
    let firstMatchRoot: string | null = null

    let current: string | null = directory
    while (current) {
      if (limit && current === limit) {
        break
      }
      if (server.rootMarkers.length > 0 && this.hasMarker(current, server.rootMarkers)) {
        buildRoot = current
      }
      if (
        !firstMatchRoot &&
        server.firstMatchMarkers.length > 0 &&
        this.hasMarker(current, server.firstMatchMarkers)
      ) {
        firstMatchRoot = current
      }
      const parent = path.dirname(current)
      if (parent === current) {
        break
      }
      current = parent
    }

    if (buildRoot) {
      return buildRoot
    }
    return firstMatchRoot ?? directory
  }

  private hasMarker(directory: string, markers: string[]): boolean {
    for (const marker of markers) {
      try {
        if (fs.existsSync(path.join(directory, marker))) {
          return true
        }
      } catch {}
    }
    return false
  }

  private findGitRoot(directory: string): string | null {
    let current: string | null = directory
    while (current) {
      try {
        if (fs.existsSync(path.join(current, '.git'))) {
          return current
        }
      } catch {}
      const parent = path.dirname(current)
      if (parent === current) {
        break
      }
      current = parent
    }
    return null
  }

  private extension(filePath: string): string | undefined {
    const name = path.basename(filePath)
    const dot = name.lastIndexOf('.')
    if (dot <= 0 || dot === name.length - 1) {
      return undefined
    }
    return name.slice(dot).toLowerCase()
  }
}
