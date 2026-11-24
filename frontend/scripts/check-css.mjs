#!/usr/bin/env node
/**
 * Style gate for the token contract described in docs/modules/frontend.md.
 *
 * Scans every stylesheet and non-test TS/TSX file under <root>/src and fails on:
 *   - var(--x) referencing a custom property that is never declared;
 *   - var(--x, fallback);
 *   - var(--palette-*) outside the token block of src/styles.css;
 *   - color literals (hex, rgb(), hsl(), ...) outside the palette block; TS files may repeat a
 *     palette hex because JS-only consumers (the KChart diagram theme) need concrete colors;
 *   - font sizes off the --font-size-* scale;
 *   - z-index above the local stacking range that is not a --z-* token;
 *   - visual debt rules tracked by a per-file, non-increasing baseline;
 *   - shared UI component classes styled outside their owning stylesheet.
 *
 * Usage: node scripts/check-css.mjs [--root <frontend dir>]
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import postcss from 'postcss'

const TOKEN_FILE = 'src/styles.css'
const BASELINE_FILE = 'scripts/check-css-baseline.json'
const DEBT_RULES = ['spacing', 'box-shadow', 'outline', 'transition-all', 'important']
const GENERIC_CLASSES = new Set(['active', 'open', 'selected', 'disabled', 'loading', 'error', 'empty', 'hidden', 'visible', 'row', 'label', 'icon', 'title', 'value', 'content', 'container'])
const PALETTE_START = '/* @palette:start */'
const PALETTE_END = '/* @palette:end */'
const FONT_SIZE_VALUE = /^(?:var\(--font-size-(?:xs|sm|md|lg|xl)\)|inherit|initial|unset)(?:\s*!important)?$/
const HEX = /(?<![&\w%#])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b/g
const COLOR_FN = /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(/g
const NAMED_COLOR =
  /(?:^|[\s;{])(?:color|background(?:-color)?|border(?:-[a-z]+)*|outline(?:-color)?|fill|stroke)\s*:[^;{}]*\b(?:white|black|red|green|blue|gray|grey|orange|yellow|purple|pink)\b/g

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(full, out)
    } else {
      out.push(full)
    }
  }
  return out
}

function isScanned(file) {
  if (/\.css$/.test(file)) {
    return true
  }
  return /\.tsx?$/.test(file) && !/\.test\.tsx?$/.test(file) && !file.includes(`${path.sep}test-support${path.sep}`)
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

/** Blank out comments while keeping offsets so reported line numbers stay accurate. */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
}

function paletteRange(text) {
  const start = text.indexOf(PALETTE_START)
  const end = text.indexOf(PALETTE_END)
  return start >= 0 && end > start ? [start, end + PALETTE_END.length] : null
}

/** The token layer is the first rule of the token file (`:root { ... }`). */
function tokenRange(text) {
  const end = text.indexOf('\n}\n')
  return end > 0 ? [0, end + 3] : null
}

export function selectorTargets(selector) {
  // Groups can contain commas and combinators of their own. Keep only the final
  // compound selector of each arm, descending into positive selector groups.
  const arms = []
  let target = []
  for (let i = 0; i < selector.length;) {
    const rest = selector.slice(i)
    const group = /^(?::([\w-]+)\(|\[)/.exec(rest)
    const className = /^\.([_a-zA-Z][\w-]*)/.exec(rest)
    if (group) {
      const endChar = group[1] ? ')' : ']'
      let depth = 1
      const start = i + group[0].length
      i = start
      while (i < selector.length && depth) {
        const quoted = /^(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')/.exec(selector.slice(i))
        if (quoted) {
          i += quoted[0].length
          continue
        }
        const char = selector[i++]
        if (group[1] && char === '(') depth++
        if (char === endChar) depth--
      }
      if (group[1] === 'is' || group[1] === 'where') target.push(...selectorTargets(selector.slice(start, i - 1)))
    } else if (className) {
      target.push(className[1])
      i += className[0].length
    } else {
      const char = selector[i++]
      if (char === ',') {
        arms.push(...target)
        target = []
      } else if (/[>+~]/.test(char) || (/\s/.test(char) && /^[^,\s]/.test(selector.slice(i).trimStart()))) {
        target = []
      }
    }
  }
  return [...new Set([...arms, ...target])]
}

function globalFocus(file, rule) {
  return file === TOKEN_FILE && rule.type === 'rule' && /^:where\(/.test(rule.selector) && rule.selector.includes(':focus')
}

const spacingAtom = /var\(\s*--space-[\w-]+\s*\)|(?<![\w.])(?:1px|0)(?![\w.])/g

function validSpacing(value) {
  const shape = value.replace(spacingAtom, '@').replace(/calc\(([^()]*)\)/g, (calc, expression) =>
    expression.includes('@') && /^[\s@+*/-]+$/.test(expression) ? '@' : calc,
  )
  return /^@(?:\s+@)*$/.test(shape)
}

export function debtCounts(file, css) {
  const counts = Object.fromEntries(DEBT_RULES.map((rule) => [rule, 0]))
  postcss.parse(css, { from: file }).walkDecls((declaration) => {
    const prop = declaration.prop.toLowerCase()
    const value = declaration.value.trim()
    if (/^(?:(?:scroll-)?(?:margin|padding)(?:-[\w-]+)?|(?:row-|column-)?gap)$/.test(prop) && !validSpacing(value)) counts.spacing++
    if (prop === 'box-shadow' && !/^(?:none|var\(\s*--shadow-[\w-]+\s*\))$/.test(value)) counts['box-shadow']++
    if (/^outline(?:-|$)/.test(prop) && !globalFocus(file, declaration.parent)) counts.outline++
    if (/^transition(?:-property)?$/.test(prop) && /(?:^|[\s,])all(?:$|[\s,])/.test(value)) counts['transition-all']++
    if (declaration.important) {
      let allowed = false
      for (let parent = declaration.parent; parent; parent = parent.parent) {
        if (parent.type === 'atrule' && parent.name === 'media' && parent.params.split(',').every((query) =>
          !/\b(?:not|or)\b/i.test(query) && /\(\s*(?:prefers-reduced-motion\s*:\s*reduce|forced-colors\s*:\s*active)\s*\)/i.test(query))) {
          allowed = true
        }
      }
      if (!allowed) counts.important++
    }
  })
  return counts
}

export function checkCss(root, { updateBaseline = false } = {}) {
  const violations = []
  const report = (file, text, index, rule, message) => {
    violations.push(`${path.relative(root, file)}:${lineOf(text, index)} [${rule}] ${message}`)
  }

  const files = walk(path.join(root, 'src')).filter(isScanned)
  const sources = files.map((file) => ({ file, text: fs.readFileSync(file, 'utf8') }))
  const baselinePath = path.join(root, BASELINE_FILE)
  const cssSources = sources.filter((source) => source.file.endsWith('.css'))
  const current = Object.fromEntries(cssSources.flatMap(({ file, text }) => {
    const relative = path.relative(root, file)
    const counts = Object.fromEntries(Object.entries(debtCounts(relative, text)).filter(([, count]) => count > 0))
    return Object.keys(counts).length ? [[relative, counts]] : []
  }))
  if (fs.existsSync(baselinePath)) {
    const baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8'))
    for (const [file, counts] of Object.entries(current)) {
      for (const [rule, count] of Object.entries(counts)) {
        const previous = baseline[file]?.[rule] ?? 0
        if (count > previous) violations.push(`${file} [${rule}] ${count} exceeds baseline ${previous}`)
      }
    }
  } else if (!updateBaseline) {
    violations.push(`${BASELINE_FILE} is missing`)
  }

  const tokenSource = sources.find((s) => path.relative(root, s.file) === TOKEN_FILE)
  const palette = tokenSource ? paletteRange(tokenSource.text) : null
  if (!tokenSource || !palette) {
    violations.push(`${TOKEN_FILE} [palette] missing ${PALETTE_START} ... ${PALETTE_END} block`)
    return violations
  }
  const tokens = tokenRange(tokenSource.text)
  const sharedOwners = new Map()
  const sheets = cssSources.map(({ file, text }) => ({
    file: path.relative(root, file), css: postcss.parse(text, { from: file }),
  }))
  for (const { file, css } of sheets) {
    if (!/^src[\\/]shared[\\/]ui[\\/](controls|cards|data|overlays)[\\/]/.test(file)) continue
    css.walkRules((rule) => {
      for (const className of selectorTargets(rule.selector)) {
        if (!GENERIC_CLASSES.has(className) && !className.startsWith('is-') && className !== 'danger' && !sharedOwners.has(className)) sharedOwners.set(className, file)
      }
    })
  }
  for (const { file, css } of sheets) {
    css.walkRules((rule) => {
      if (globalFocus(file, rule)) return
      for (const className of selectorTargets(rule.selector)) {
        const owner = sharedOwners.get(className)
        if (owner && owner !== file) violations.push(`${file}:${rule.source.start.line} [shared-owner] .${className} belongs to ${owner}`)
      }
    })
  }

  const paletteHexes = new Set(
    [...tokenSource.text.slice(palette[0], palette[1]).matchAll(HEX)].map((m) => m[0].toLowerCase()),
  )

  const declared = new Set()
  for (const { file, text } of sources) {
    const isCss = file.endsWith('.css')
    const pattern = isCss ? /(--[A-Za-z][\w-]*)\s*:/g : /['"`](--[A-Za-z][\w-]*)['"`]|(--[A-Za-z][\w-]*)\s*:/g
    for (const m of text.matchAll(pattern)) {
      declared.add(m[1] ?? m[2])
    }
  }

  for (const { file, text: raw } of sources) {
    const isCss = file.endsWith('.css')
    const isTokenFile = file === tokenSource.file
    const text = stripComments(raw)

    for (const m of text.matchAll(/var\(\s*(--[A-Za-z][\w-]*)\s*(,)?/g)) {
      const name = m[1]
      if (m[2]) {
        report(file, text, m.index, 'fallback', `var(${name}, fallback) is not allowed; define the token instead`)
      }
      if (!declared.has(name)) {
        report(file, text, m.index, 'undefined-var', `${name} is never declared`)
      }
      const inTokens = isTokenFile && tokens && m.index < tokens[1]
      if (name.startsWith('--palette-') && !inTokens) {
        report(file, text, m.index, 'palette-layer', `${name} is palette-only; use a semantic token`)
      }
    }

    const inPalette = (index) => isTokenFile && index >= palette[0] && index < palette[1]
    for (const m of text.matchAll(HEX)) {
      if (inPalette(m.index)) {
        continue
      }
      if (!isCss && paletteHexes.has(m[0].toLowerCase())) {
        continue
      }
      report(file, text, m.index, 'raw-color', `${m[0]} is outside the palette layer`)
    }
    for (const m of text.matchAll(COLOR_FN)) {
      report(file, text, m.index, 'raw-color', `${m[0]}) is outside the palette layer`)
    }
    if (isCss) {
      for (const m of text.matchAll(NAMED_COLOR)) {
        report(file, text, m.index, 'raw-color', `named color in "${m[0].trim()}"`)
      }
    }

    const fontPattern = isCss
      ? /(?<![\w-])font-size\s*:\s*([^;}\n]+)/g
      : /(?:fontSize\s*:\s*|font-size\s*:\s*)(['"`]?)([^'"`;,}\n]+)\1/g
    for (const m of text.matchAll(fontPattern)) {
      const value = (isCss ? m[1] : m[2]).trim()
      if (!FONT_SIZE_VALUE.test(value)) {
        report(file, text, m.index, 'font-size', `"${value}" is off the --font-size-* scale`)
      }
    }
    if (isCss) {
      for (const m of text.matchAll(/(?<![\w-])font\s*:\s*([^;}\n]+)/g)) {
        if (m[1].trim() !== 'inherit') {
          report(file, text, m.index, 'font-size', `font shorthand "${m[1].trim()}" hides the size; use longhands`)
        }
      }
      for (const m of text.matchAll(/(?<![\w-])z-index\s*:\s*([^;}\n]+)/g)) {
        const value = m[1].trim()
        const local = /^-?\d+$/.test(value) && Math.abs(Number(value)) <= 10
        if (!local && value !== 'auto' && !/^var\(--z-[\w-]+\)$/.test(value)) {
          report(file, text, m.index, 'z-index', `"${value}" must be 0-10 or a --z-* token`)
        }
      }
    }
  }

  if (updateBaseline && !violations.length) fs.writeFileSync(baselinePath, `${JSON.stringify(current, null, 2)}\n`)
  return violations
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : ''
if (entry === fileURLToPath(import.meta.url)) {
  const flag = process.argv.indexOf('--root')
  const root = path.resolve(flag > 0 ? process.argv[flag + 1] : path.join(path.dirname(entry), '..'))
  const violations = checkCss(root, { updateBaseline: process.argv.includes('--update-baseline') })
  if (violations.length > 0) {
    console.error(violations.join('\n'))
    console.error(`\n${violations.length} style violation(s)`)
    process.exit(1)
  }
  console.log('style gate passed')
}
