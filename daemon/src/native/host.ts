/**
 * 业务代码读取宿主平台事实的唯一入口。
 *
 * <p>各能力不直接读取 `process.platform`。需要当前宿主、发行 OS 或路径大小写规则时统一通过本模块获取；
 * native 适配层内部仍按平台分发自己的系统调用。
 */

/** 当前宿主是否为 Windows。 */
export function isWindows(): boolean {
  return process.platform === 'win32'
}

/** 当前宿主是否使用 POSIX 文件权限与 Unix socket。 */
export function isPosixHost(): boolean {
  return process.platform !== 'win32'
}

/** 文件系统路径比较是否忽略大小写。Windows 路径键统一折叠，其它宿主保持原样。 */
export function pathKeysIgnoreCase(): boolean {
  return process.platform === 'win32'
}

/** 冻结发行里 code-server 自带的 Node 可执行文件名。 */
export function bundledNodeExecutableName(): string {
  return process.platform === 'win32' ? 'node.exe' : 'node'
}

/** helper 建立执行范围时的平台阶段名。 */
export function processScopePhase(): 'windows' | 'posix' {
  return process.platform === 'win32' ? 'windows' : 'posix'
}
