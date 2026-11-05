import type { Stats } from 'node:fs'

/**
 * 文件系统目标类型判定：只认普通目录与普通文件。
 *
 * <p>输入通常是 lstat 结果，此时符号链接本身（含 Windows reparse point）不算目录或文件；stat 结果已跟随链接。
 * 设备、FIFO、socket 同样不算。私有文件系统原语与文件/检索能力共用这一份语义，不再各自拼
 * `isDirectory()` 与 `isSymbolicLink()`。
 */

/** 是否为真实目录（不是符号链接）。 */
export function isRealDirectory(info: Stats): boolean {
  return info.isDirectory() && !info.isSymbolicLink()
}

/** 是否为普通文件（不是符号链接）。 */
export function isRealFile(info: Stats): boolean {
  return info.isFile() && !info.isSymbolicLink()
}
