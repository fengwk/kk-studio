/**
 * Minimal ustar writer/reader helpers for daemon archive fixtures.
 *
 * Fixtures need archives with exact member names (including hostile ones such as `../escape` or an
 * absolute path), which the system `tar` refuses to create, so the writer builds the bytes itself.
 */

import { spawnSync } from 'node:child_process'
import { lstatSync, readFileSync, readdirSync, readlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { gzipSync } from 'node:zlib'

const BLOCK = 512

function field(value, length) {
  const buffer = Buffer.alloc(length)
  buffer.write(value, 0, length, 'utf8')
  return buffer
}

function octal(value, length) {
  return field(value.toString(8).padStart(length - 1, '0'), length)
}

/** Split a long name into ustar `prefix` and `name` at a path separator. */
function splitName(name) {
  if (Buffer.byteLength(name) <= 100) {
    return ['', name]
  }
  for (let index = name.indexOf('/'); index !== -1; index = name.indexOf('/', index + 1)) {
    if (Buffer.byteLength(name.slice(0, index)) <= 155 && Buffer.byteLength(name.slice(index + 1)) <= 100) {
      return [name.slice(0, index), name.slice(index + 1)]
    }
  }
  throw new Error(`tar member name too long: ${name}`)
}

function header({ name, type, size, mode, linkname }) {
  const [prefix, base] = splitName(name)
  const block = Buffer.alloc(BLOCK)
  field(base, 100).copy(block, 0)
  octal(mode, 8).copy(block, 100)
  octal(0, 8).copy(block, 108)
  octal(0, 8).copy(block, 116)
  octal(size, 12).copy(block, 124)
  octal(Math.floor(Date.now() / 1000), 12).copy(block, 136)
  block.fill(0x20, 148, 156)
  block[156] = type.charCodeAt(0)
  field(linkname ?? '', 100).copy(block, 157)
  field('ustar\0', 6).copy(block, 257)
  field('00', 2).copy(block, 263)
  field(prefix, 155).copy(block, 345)
  const checksum = block.reduce((sum, byte) => sum + byte, 0)
  field(`${checksum.toString(8).padStart(6, '0')}\0 `, 8).copy(block, 148)
  return block
}

/**
 * Write a gzip-compressed tar. Each entry is `{ name, type: 'file'|'dir'|'symlink', content?, mode?, linkname? }`.
 */
export function writeTarGz(file, entries) {
  const blocks = []
  for (const entry of entries) {
    const type = { file: '0', dir: '5', symlink: '2' }[entry.type ?? 'file']
    const content = type === '0' ? Buffer.from(entry.content ?? '') : Buffer.alloc(0)
    const name = type === '5' && !entry.name.endsWith('/') ? `${entry.name}/` : entry.name
    blocks.push(
      header({
        name,
        type,
        size: content.length,
        mode: entry.mode ?? (type === '5' ? 0o755 : 0o644),
        linkname: entry.linkname,
      }),
    )
    if (content.length > 0) {
      blocks.push(content, Buffer.alloc((BLOCK - (content.length % BLOCK)) % BLOCK))
    }
  }
  blocks.push(Buffer.alloc(BLOCK * 2))
  writeFileSync(file, gzipSync(Buffer.concat(blocks)))
}

/** Entries for `directory` and everything below it, named relative to `arcname`. */
export function treeEntries(directory, arcname) {
  const entries = []
  const visit = (current, name) => {
    const stat = lstatSync(current)
    if (stat.isSymbolicLink()) {
      entries.push({ name, type: 'symlink', linkname: readlinkSync(current), mode: 0o777 })
    } else if (stat.isDirectory()) {
      entries.push({ name, type: 'dir', mode: stat.mode & 0o777 })
      for (const child of readdirSync(current).sort()) {
        visit(path.join(current, child), `${name}/${child}`)
      }
    } else {
      entries.push({ name, type: 'file', content: readFileSync(current), mode: stat.mode & 0o777 })
    }
  }
  visit(directory, arcname)
  return entries
}

/** Member names of a tar.gz as listed by the system tar, with leading `./` and `/` removed. */
export function tarNames(file) {
  const result = spawnSync('tar', ['-tzf', file], { encoding: 'utf8' })
  if (result.status !== 0) {
    throw new Error(`tar -tzf failed: ${result.stderr}`)
  }
  return result.stdout
    .split('\n')
    .filter(Boolean)
    .map((name) => name.replace(/^[./]+/, ''))
}
