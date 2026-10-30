import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'

/**
 * JaCoCo 规则匹配用的 Java 主源码索引与命名规约。
 *
 * <p>`BundleChecker` 在匹配 CLASS/PACKAGE 规则前把 `$` 归一为 `.`，因此嵌套类型必须按
 * `Outer.Nested` 这类限定名建模；本模块从源码结构提取同一命名空间，供仓库门禁测试复用。
 */

const JAVA_TYPE_KEYWORDS = new Set(['class', 'interface', 'enum', 'record'])

/**
 * 剥离 Java 注释与字符串/字符/文本块字面量（用空格占位以免粘连相邻 token），保留其余结构字符，
 * 使嵌套类型的花括号配对扫描不受其中内容干扰。
 */
function stripJavaCommentsAndLiterals(source) {
  let stripped = ''
  let index = 0
  while (index < source.length) {
    const char = source[index]
    const next = source[index + 1]
    if (char === '/' && next === '/') {
      while (index < source.length && source[index] !== '\n') {
        index += 1
      }
      stripped += ' '
      continue
    }
    if (char === '/' && next === '*') {
      index += 2
      while (index < source.length && !(source[index] === '*' && source[index + 1] === '/')) {
        index += 1
      }
      index += 2
      stripped += ' '
      continue
    }
    if (char === '"' && next === '"' && source[index + 2] === '"') {
      index += 3
      while (
        index < source.length &&
        !(source[index] === '"' && source[index + 1] === '"' && source[index + 2] === '"')
      ) {
        index += source[index] === '\\' ? 2 : 1
      }
      index += 3
      stripped += ' '
      continue
    }
    if (char === '"' || char === "'") {
      index += 1
      while (index < source.length && source[index] !== char) {
        index += source[index] === '\\' ? 2 : 1
      }
      index += 1
      stripped += ' '
      continue
    }
    stripped += char
    index += 1
  }
  return stripped
}

/**
 * 提取顶层类型内声明的嵌套类型限定名（相对顶层，例如 `Nested`、`Nested.Deeper`），与 JaCoCo
 * 把 `$` 归一为 `.` 后的 CLASS 名称一致。只统计类型成员体中的声明：方法体内的局部类在 JaCoCo
 * 中是 `Outer$1Local`，不属于这个命名空间。
 */
export function nestedTypeNames(source) {
  const code = stripJavaCommentsAndLiterals(source)
  const names = new Set()
  // 每层花括号一项：类型体为类型名，其他块（方法体、初始化块、匿名类体等）为 null。
  const stack = []
  let pendingType = null
  let previousSignificant = ''
  let index = 0
  while (index < code.length) {
    const char = code[index]
    if (char === '{') {
      stack.push(pendingType)
      pendingType = null
      previousSignificant = char
      index += 1
      continue
    }
    if (char === '}') {
      stack.pop()
      previousSignificant = char
      index += 1
      continue
    }
    if (/[A-Za-z_$]/u.test(char)) {
      let end = index
      while (end < code.length && /[A-Za-z0-9_$]/u.test(code[end])) {
        end += 1
      }
      const word = code.slice(index, end)
      // `.class` 字面量不引入类型声明。
      if (JAVA_TYPE_KEYWORDS.has(word) && previousSignificant !== '.') {
        let nameStart = end
        while (nameStart < code.length && /\s/u.test(code[nameStart])) {
          nameStart += 1
        }
        let nameEnd = nameStart
        while (nameEnd < code.length && /[A-Za-z0-9_$]/u.test(code[nameEnd])) {
          nameEnd += 1
        }
        const typeName = code.slice(nameStart, nameEnd)
        if (typeName) {
          // stack[0] 是文件顶层类型，只有它之内的声明才是嵌套类型。
          const enclosing = stack.slice(1)
          if (stack.length > 0 && enclosing.every((entry) => entry !== null)) {
            names.add(enclosing.length > 0 ? `${enclosing.join('.')}.${typeName}` : typeName)
          }
          pendingType = typeName
        }
      }
      previousSignificant = code[end - 1]
      index = end
      continue
    }
    if (!/\s/u.test(char)) {
      previousSignificant = char
    }
    index += 1
  }
  return names
}

/** 主源码类/包索引（不含 package-info / module-info：它们不是 capability 目标类）。 */
export function indexMainSources(moduleDirectory) {
  const sourceRoot = path.join(moduleDirectory, 'src/main/java')
  const classes = new Set()
  const packages = new Set()
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const absolutePath = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        walk(absolutePath)
        continue
      }
      if (!entry.name.endsWith('.java') || /^(?:package|module)-info\.java$/u.test(entry.name)) {
        continue
      }
      const qualifiedName = path
        .relative(sourceRoot, absolutePath)
        .slice(0, -'.java'.length)
        .split(path.sep)
        .join('.')
      classes.add(qualifiedName)
      packages.add(qualifiedName.split('.').slice(0, -1).join('.'))
      // 嵌套类型没有独立源文件，必须按 `Outer.Nested` 限定名补入类命名空间。
      for (const nested of nestedTypeNames(readFileSync(absolutePath, 'utf8'))) {
        classes.add(`${qualifiedName}.${nested}`)
      }
    }
  }
  if (existsSync(sourceRoot)) {
    walk(sourceRoot)
  }
  return { classes, packages }
}

/** 复刻 `org.jacoco.core.runtime.WildcardMatcher`：`:` 分隔候选，`*` 任意字符，`?` 单字符，其余字面量，整体匹配。 */
export function jacocoPatternToRegExp(pattern) {
  const alternatives = pattern.split(':').map((part) => {
    let regex = ''
    for (const char of part) {
      if (char === '*') {
        regex += '.*'
      } else if (char === '?') {
        regex += '.'
      } else {
        regex += char.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
      }
    }
    return `(${regex})`
  })
  return new RegExp(`^${alternatives.join('|')}$`, 'u')
}

/** 合法限定名模式：不许 `/`（不会命中）、不许 `$`（限定名规约要求嵌套类用 `.`）、不许 `:`；每段为非空标识符/通配符。 */
export function isQualifiedNamePattern(pattern) {
  if (pattern.length === 0 || /[/$:]/u.test(pattern)) {
    return false
  }
  return pattern.split('.').every((segment) => /^[A-Za-z0-9_*?]+$/u.test(segment))
}

/** 关键类门禁只接受精确类名：不含通配符，嵌套类型写 `.` 而不是 `$`。 */
export function isExactClassName(value) {
  return /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/u.test(value)
}

export function matchesAny(pattern, names) {
  const matcher = jacocoPatternToRegExp(pattern)
  for (const name of names) {
    if (matcher.test(name)) {
      return true
    }
  }
  return false
}
