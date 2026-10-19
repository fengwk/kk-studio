#!/usr/bin/env node
/**
 * Style gate for the token contract described in docs/modules/frontend-design-language.md.
 *
 * Scans every stylesheet and non-test TS/TSX file under <root>/src and fails on:
 *   - var(--x) referencing a custom property that is never declared;
 *   - var(--x, fallback);
 *   - var(--palette-*) outside the token block of src/styles.css;
 *   - color literals (hex, rgb(), hsl(), ...) outside the palette block; TS files may repeat a
 *     palette hex because JS-only consumers (Mermaid) need concrete colors;
 *   - font sizes off the --font-size-* scale;
 *   - z-index above the local stacking range that is not a --z-* token.
 *
 * Usage: node scripts/check-css.mjs [--root <frontend dir>]
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const TOKEN_FILE = 'src/styles.css'
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

export function checkCss(root) {
  const violations = []
  const report = (file, text, index, rule, message) => {
    violations.push(`${path.relative(root, file)}:${lineOf(text, index)} [${rule}] ${message}`)
  }

  const files = walk(path.join(root, 'src')).filter(isScanned)
  const sources = files.map((file) => ({ file, text: fs.readFileSync(file, 'utf8') }))

  const tokenSource = sources.find((s) => path.relative(root, s.file) === TOKEN_FILE)
  const palette = tokenSource ? paletteRange(tokenSource.text) : null
  if (!tokenSource || !palette) {
    violations.push(`${TOKEN_FILE} [palette] missing ${PALETTE_START} ... ${PALETTE_END} block`)
    return violations
  }
  const tokens = tokenRange(tokenSource.text)
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
      // JS-only consumers (Mermaid) cannot resolve var(); they may repeat a scale step in px.
      const jsScaleStep = !isCss && /^(?:12|13|15|20|24)px$/.test(value)
      if (!jsScaleStep && !FONT_SIZE_VALUE.test(value)) {
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

  return violations
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : ''
if (entry === fileURLToPath(import.meta.url)) {
  const flag = process.argv.indexOf('--root')
  const root = path.resolve(flag > 0 ? process.argv[flag + 1] : path.join(path.dirname(entry), '..'))
  const violations = checkCss(root)
  if (violations.length > 0) {
    console.error(violations.join('\n'))
    console.error(`\n${violations.length} style violation(s)`)
    process.exit(1)
  }
  console.log('style gate passed')
}
