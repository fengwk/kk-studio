import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  decodeCloudBlobMetadata,
  decodeCloudFileSnapshot,
  decodeCloudNode,
  decodeCloudTextRevision,
  decodeCloudTextRevisionLine,
  isCanonicalUuid,
  isCloudNodeKind,
  isDecimalLong,
} from './codecs'

const VALID_UUID = '12345678-1234-1234-1234-123456789abc'
const VALID_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

describe('cloud-files codecs', () => {
  describe('primitives', () => {
    it('validates UUIDs', () => {
      expect(isCanonicalUuid(VALID_UUID)).toBe(true)
      expect(isCanonicalUuid('invalid-uuid')).toBe(false)
      expect(isCanonicalUuid(123)).toBe(false)
    })

    it('validates decimal longs', () => {
      expect(isDecimalLong('0')).toBe(true)
      expect(isDecimalLong('42')).toBe(true)
      expect(isDecimalLong('012')).toBe(false) // leading zero not allowed unless single 0
      expect(isDecimalLong('-1')).toBe(false)
      expect(isDecimalLong('abc')).toBe(false)
    })

    it('validates cloud node kind', () => {
      expect(isCloudNodeKind('DIRECTORY')).toBe(true)
      expect(isCloudNodeKind('TEXT')).toBe(true)
      expect(isCloudNodeKind('BLOB')).toBe(true)
      expect(isCloudNodeKind('OTHER')).toBe(false)
    })
  })

  describe('decodeCloudNode', () => {
    it('decodes a valid directory node', () => {
      const raw = {
        id: VALID_UUID,
        path: '/docs',
        name: 'docs',
        kind: 'DIRECTORY',
        version: '1',
        blobId: null,
        mediaType: null,
        sizeBytes: null,
        sha256: null,
        revision: null,
        createdAt: '2026-09-13T00:00:00Z',
        updatedAt: '2026-09-13T00:00:00Z',
      }
      const node = decodeCloudNode(raw)
      expect(node.name).toBe('docs')
      expect(node.kind).toBe('DIRECTORY')
      expect(node.version).toBe('1')
    })

    it('decodes root directory node where id may be null', () => {
      const raw = {
        id: null,
        path: '/',
        name: '',
        kind: 'DIRECTORY',
        version: '0',
        blobId: null,
        mediaType: null,
        sizeBytes: null,
        sha256: null,
        revision: null,
        createdAt: null,
        updatedAt: null,
      }
      const node = decodeCloudNode(raw)
      expect(node.id).toBeNull()
      expect(node.path).toBe('/')
      expect(node.kind).toBe('DIRECTORY')
    })

    it('rejects undefined in nullable fields', () => {
      const raw = {
        id: VALID_UUID,
        path: '/test',
        name: 'test',
        kind: 'TEXT',
        version: '1',
        blobId: undefined, // undefined must be rejected
        mediaType: null,
        sizeBytes: null,
        sha256: null,
        revision: null,
        createdAt: null,
        updatedAt: null,
      }
      expect(() => decodeCloudNode(raw)).toThrow('blobId must not be undefined')
    })

    it('decodes a valid blob node with fields', () => {
      const raw = {
        id: VALID_UUID,
        path: '/image.png',
        name: 'image.png',
        kind: 'BLOB',
        version: '2',
        blobId: VALID_UUID,
        mediaType: 'image/png',
        sizeBytes: '1024',
        sha256: VALID_SHA256,
        revision: null,
        createdAt: null,
        updatedAt: null,
      }
      const node = decodeCloudNode(raw)
      expect(node.kind).toBe('BLOB')
      expect(node.blobId).toBe(VALID_UUID)
      expect(node.mediaType).toBe('image/png')
      expect(node.sizeBytes).toBe('1024')
    })

    it('rejects invalid uuid in node.id', () => {
      const raw = {
        id: 'not-a-uuid',
        path: '/test',
        name: 'test',
        kind: 'TEXT',
        version: '0',
      }
      expect(() => decodeCloudNode(raw)).toThrow(ApiError)
    })

    it('rejects path not starting with /', () => {
      const raw = {
        id: VALID_UUID,
        path: 'relative/path',
        name: 'relative',
        kind: 'DIRECTORY',
        version: '0',
      }
      expect(() => decodeCloudNode(raw)).toThrow('must start with /')
    })

    it('rejects invalid kind', () => {
      const raw = {
        id: VALID_UUID,
        path: '/test',
        name: 'test',
        kind: 'SYMLINK',
        version: '0',
      }
      expect(() => decodeCloudNode(raw)).toThrow('kind must be DIRECTORY, TEXT, or BLOB')
    })

    it('rejects invalid version string', () => {
      const raw = {
        id: VALID_UUID,
        path: '/test',
        name: 'test',
        kind: 'DIRECTORY',
        version: 'version_1',
      }
      expect(() => decodeCloudNode(raw)).toThrow('must be a canonical non-negative decimal string')
    })
  })

  describe('decodeCloudTextRevision', () => {
    it('decodes text revision with lines', () => {
      const raw = {
        revision: '1',
        endsWithNewline: true,
        offset: 1,
        totalLines: 2,
        nextOffset: null,
        lines: [
          { lineNumber: 1, content: 'Hello', truncated: false },
          { lineNumber: 2, content: 'World', truncated: false },
        ],
      }
      const text = decodeCloudTextRevision(raw)
      expect(text.revision).toBe('1')
      expect(text.lines).toHaveLength(2)
      expect(text.lines[0].content).toBe('Hello')
    })

    it('rejects invalid line numbers', () => {
      const raw = {
        lineNumber: 0,
        content: 'Bad line number',
        truncated: false,
      }
      expect(() => decodeCloudTextRevisionLine(raw)).toThrow(ApiError)
    })
  })

  describe('decodeCloudBlobMetadata', () => {
    it('decodes blob metadata', () => {
      const raw = {
        blobId: VALID_UUID,
        mediaType: 'application/json',
        sizeBytes: '500',
        sha256: VALID_SHA256,
      }
      const blob = decodeCloudBlobMetadata(raw)
      expect(blob.blobId).toBe(VALID_UUID)
      expect(blob.sizeBytes).toBe('500')
    })

    it('rejects invalid sha256', () => {
      const raw = {
        blobId: VALID_UUID,
        mediaType: 'text/plain',
        sizeBytes: '10',
        sha256: 'short-sha',
      }
      expect(() => decodeCloudBlobMetadata(raw)).toThrow('64-character hex')
    })
  })

  describe('decodeCloudFileSnapshot', () => {
    it('decodes directory snapshot with children', () => {
      const raw = {
        node: {
          id: VALID_UUID,
          path: '/',
          name: '',
          kind: 'DIRECTORY',
          version: '0',
          blobId: null,
          mediaType: null,
          sizeBytes: null,
          sha256: null,
          revision: null,
          createdAt: null,
          updatedAt: null,
        },
        children: [
          {
            id: VALID_UUID,
            path: '/readme.md',
            name: 'readme.md',
            kind: 'TEXT',
            version: '1',
            blobId: null,
            mediaType: 'text/markdown',
            sizeBytes: '100',
            sha256: null,
            revision: '1',
            createdAt: null,
            updatedAt: null,
          },
        ],
      }
      const snapshot = decodeCloudFileSnapshot(raw)
      expect(snapshot.node.kind).toBe('DIRECTORY')
      expect(snapshot.children).toHaveLength(1)
      expect(snapshot.children?.[0].name).toBe('readme.md')
    })

    it('decodes text snapshot', () => {
      const raw = {
        node: {
          id: VALID_UUID,
          path: '/readme.md',
          name: 'readme.md',
          kind: 'TEXT',
          version: '1',
          blobId: null,
          mediaType: 'text/markdown',
          sizeBytes: '20',
          sha256: null,
          revision: '1',
          createdAt: null,
          updatedAt: null,
        },
        text: {
          revision: '1',
          endsWithNewline: false,
          offset: 1,
          totalLines: 1,
          nextOffset: null,
          lines: [{ lineNumber: 1, content: '# Hello', truncated: false }],
        },
      }
      const snapshot = decodeCloudFileSnapshot(raw)
      expect(snapshot.node.kind).toBe('TEXT')
      expect(snapshot.text?.lines[0].content).toBe('# Hello')
    })
  })
})
