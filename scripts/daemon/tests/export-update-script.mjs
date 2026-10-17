#!/usr/bin/env node

// 意图：把 daemon/src/update-scripts.ts 里某一个已知托管更新脚本按原字节导出为 UTF-8 文件，
// 让 PowerShell 与 Python fixture 通过同一个入口读取真实生产脚本文本，而不是各自复制一份。
//
// 为什么不把 JavaScript 源码写在命令行里：Windows PowerShell 5.1 会吞掉 native argv 里的引号，
// 含引号的 `-e '<source>'` 到 Node 手里只剩去引号后的碎片（CI 实证为 SyntaxError）；5.1 还会按
// 控制台代码页解码 native stdout，把正文里的中文注释改写成乱码。这里只接受路径与导出名，
// 正文既不经过命令行也不经过 stdout，两种宿主都拿到逐字节一致的 UTF-8 文本。
//
// Node 24 能直接以类型剥离加载这个无 import、只导出模板字符串的模块，因此无需构建产物。

import { writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

/** 只暴露真正的托管更新脚本，避免这个入口退化成通用的模块导出转储。 */
const UPDATE_SCRIPT_EXPORTS = new Set(['UNIX_UPDATE_SCRIPT', 'WINDOWS_UPDATE_SCRIPT'])

const [modulePath, exportName, outputPath] = process.argv.slice(2)
if (modulePath === undefined || exportName === undefined || outputPath === undefined) {
  throw new Error(
    'usage: node export-update-script.mjs <update-scripts.ts> <exportName> <outputPath>',
  )
}
if (!UPDATE_SCRIPT_EXPORTS.has(exportName)) {
  throw new Error(`unknown update script export: ${exportName}`)
}

// pathToFileURL 让 Windows 盘符与含空格的路径都成为合法的模块 URL。
const updateScripts = await import(pathToFileURL(modulePath).href)
const exported = updateScripts[exportName]
if (typeof exported !== 'string' || exported === '') {
  throw new Error(`${exportName} is not a non-empty string export of ${modulePath}`)
}

// 无 BOM 的 UTF-8，LF 原样保留：写文件不会产生 stdout 那样的编码往返。
writeFileSync(outputPath, exported, 'utf8')
