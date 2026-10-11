import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

// 用独立实现持有目标目录的内核 LockFileEx：验证父进程的锁是真实内核锁，且崩溃时由内核释放。
const koffi = (await import('koffi')).default
const kernel32 = koffi.load('kernel32.dll')
const OVERLAPPED = koffi.struct('OVERLAPPED', {
  Internal: 'size_t',
  InternalHigh: 'size_t',
  Offset: 'uint32_t',
  OffsetHigh: 'uint32_t',
  hEvent: 'void *',
})
const CreateFileW = kernel32.func(
  'void *CreateFileW(const char16_t *fileName, unsigned int desiredAccess, unsigned int shareMode, void *securityAttributes, unsigned int creationDisposition, unsigned int flagsAndAttributes, void *template)',
)
const LockFileEx = kernel32.func('int LockFileEx(void *file, unsigned int flags, unsigned int reserved, unsigned int low, unsigned int high, OVERLAPPED *overlapped)')
const CloseHandle = kernel32.func('int CloseHandle(void *handle)')

const lockDir = process.argv[2]
const readyFile = process.argv[3]
const handle = CreateFileW(join(lockDir, 'daemon.lock'), 0x80000000 | 0x40000000, 1 | 2, null, 4, 0x80, null)
const overlapped = { Internal: 0n, InternalHigh: 0n, Offset: 0, OffsetHigh: 0, hEvent: null }
if (LockFileEx(handle, 2 | 1, 0, 1, 0, overlapped) === 0) {
  writeFileSync(readyFile, 'blocked')
  CloseHandle(handle)
  process.exit(3)
}
writeFileSync(readyFile, String(process.pid))
setInterval(() => {}, 1 << 30)
