export type CloudNodeKind = 'DIRECTORY' | 'TEXT' | 'BLOB'

export interface CloudNodeDTO {
  id: string | null
  path: string
  name: string
  kind: CloudNodeKind
  version: string
  blobId: string | null
  mediaType: string | null
  sizeBytes: string | null
  sha256: string | null
  revision: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface CloudTextRevisionLineDTO {
  lineNumber: number
  content: string
  truncated: boolean
}

export interface CloudTextRevisionDTO {
  revision: string
  endsWithNewline: boolean
  offset: number
  totalLines: number
  nextOffset: number | null
  lines: CloudTextRevisionLineDTO[]
}

export type CloudTextWindowDTO = CloudTextRevisionDTO

export interface CloudBlobMetadataDTO {
  blobId: string
  mediaType: string | null
  sizeBytes: string | null
  sha256: string | null
}

export interface CloudFileSnapshotDTO {
  node: CloudNodeDTO
  children: CloudNodeDTO[] | null
  text: CloudTextRevisionDTO | null
  blob: CloudBlobMetadataDTO | null
}

export interface SaveTextRequest {
  path: string
  content: string
  expectedRevision: string
}

export interface PatchTextRequest {
  path: string
  oldString: string
  newString: string
  replaceAll: boolean
  expectedRevision: string
}

export interface CreateDirectoryRequest {
  path: string
  recursive?: boolean
}

export interface MoveNodeRequest {
  sourcePath: string
  destinationPath: string
  expectedVersion: string
}

export interface DeleteNodeParams {
  path: string
  expectedVersion: string
}

export interface MountBlobRequest {
  path: string
  uploadId: string
  expectedAbsent: true
}

export interface CloudFilesChangedEventPayload {
  affectedPaths?: string[]
}
