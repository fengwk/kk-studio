/**
 * 写满整个缓冲区的唯一实现。
 *
 * <p>同步 `writeSync` 与异步 `FileHandle.write` 都允许短写。循环补齐前绝不认为已经写完；
 * `bytesWritten <= 0` 视为无法推进并按调用方给定的文案失败关闭。
 */

/** 同步写满端口：Node `fs.writeSync` 的窄切片。 */
export interface SyncWriter {
  writeSync(fd: number, buffer: Buffer, offset: number, length: number): number
}

/** 异步写满端口：Node `FileHandle.write` 的窄切片。 */
export interface AsyncWriter {
  write(buffer: Uint8Array, offset: number, length: number): Promise<{ bytesWritten: number }>
}

/** 同步写满一个文件描述符。 */
export function writeAllSync(
  writer: SyncWriter,
  descriptor: number,
  bytes: Buffer,
  stalledMessage: string,
): void {
  let offset = 0
  while (offset < bytes.length) {
    const written = writer.writeSync(descriptor, bytes, offset, bytes.length - offset)
    if (written <= 0) {
      throw new Error(stalledMessage)
    }
    offset += written
  }
}

/** 异步写满一个文件句柄。 */
export async function writeAll(writer: AsyncWriter, bytes: Uint8Array, stalledMessage: string): Promise<void> {
  let offset = 0
  while (offset < bytes.length) {
    const { bytesWritten } = await writer.write(bytes, offset, bytes.length - offset)
    if (bytesWritten <= 0) {
      throw new Error(stalledMessage)
    }
    offset += bytesWritten
  }
}
