import { createHash } from 'node:crypto'
import { crc32 } from 'node:zlib'

export interface ZipFixtureEntry {
  name: string
  content?: string | Uint8Array
  mode?: number // e.g. 0o120777 for symlink, 0o100644 for regular file, 0o040755 for directory
  isDir?: boolean
  versionMadeBy?: number // default (3 << 8) | 20 for UNIX
  externalAttrs?: number
}

export function buildZipFixture(entries: ZipFixtureEntry[]): Buffer {
  const parts: Buffer[] = []
  const cdEntries: Buffer[] = []
  let offset = 0

  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, 'utf8')
    const content = Buffer.isBuffer(entry.content)
      ? entry.content
      : typeof entry.content === 'string'
        ? Buffer.from(entry.content, 'utf8')
        : entry.content instanceof Uint8Array
          ? Buffer.from(entry.content)
          : Buffer.alloc(0)
    const crc = crc32(content)
    const mode =
      entry.mode !== undefined
        ? entry.mode
        : entry.isDir || entry.name.endsWith('/')
          ? 0o040755
          : 0o100644
    const externalAttrs =
      entry.externalAttrs !== undefined ? entry.externalAttrs : (mode << 16) >>> 0
    const versionMadeBy =
      entry.versionMadeBy !== undefined ? entry.versionMadeBy : (3 << 8) | 20

    const localHeader = Buffer.alloc(30)
    localHeader.writeUInt32LE(0x04034b50, 0)
    localHeader.writeUInt16LE(20, 4)
    localHeader.writeUInt16LE(0, 6)
    localHeader.writeUInt16LE(0, 8) // stored (no compression)
    localHeader.writeUInt16LE(0, 10)
    localHeader.writeUInt16LE(0, 12)
    localHeader.writeUInt32LE(crc, 14)
    localHeader.writeUInt32LE(content.length, 18)
    localHeader.writeUInt32LE(content.length, 22)
    localHeader.writeUInt16LE(nameBuf.length, 26)
    localHeader.writeUInt16LE(0, 28)

    parts.push(localHeader, nameBuf, content)

    const cdHeader = Buffer.alloc(46)
    cdHeader.writeUInt32LE(0x02014b50, 0)
    cdHeader.writeUInt16LE(versionMadeBy, 4)
    cdHeader.writeUInt16LE(20, 6)
    cdHeader.writeUInt16LE(0, 8)
    cdHeader.writeUInt16LE(0, 10)
    cdHeader.writeUInt16LE(0, 12)
    cdHeader.writeUInt16LE(0, 14)
    cdHeader.writeUInt32LE(crc, 16)
    cdHeader.writeUInt32LE(content.length, 20)
    cdHeader.writeUInt32LE(content.length, 24)
    cdHeader.writeUInt16LE(nameBuf.length, 28)
    cdHeader.writeUInt16LE(0, 30)
    cdHeader.writeUInt16LE(0, 32)
    cdHeader.writeUInt16LE(0, 34)
    cdHeader.writeUInt16LE(0, 36)
    cdHeader.writeUInt32LE(externalAttrs, 38)
    cdHeader.writeUInt32LE(offset, 42)

    cdEntries.push(cdHeader, nameBuf)
    offset += localHeader.length + nameBuf.length + content.length
  }

  const cdStart = offset
  let cdSize = 0
  for (const buf of cdEntries) {
    cdSize += buf.length
  }

  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(0, 4)
  eocd.writeUInt16LE(0, 6)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(cdSize, 12)
  eocd.writeUInt32LE(cdStart, 16)
  eocd.writeUInt16LE(0, 20)

  return Buffer.concat([...parts, ...cdEntries, eocd])
}

export function sha256Of(buf: Buffer | Uint8Array): string {
  return createHash('sha256').update(buf).digest('hex').toLowerCase()
}
