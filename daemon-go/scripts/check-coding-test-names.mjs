#!/usr/bin/env node
// Ensure every Go test name claimed by a coding T row exists in this worktree.
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const [specPath, goRoot] = process.argv.slice(2)
if (!specPath || !goRoot) {
  console.error('usage: check-coding-test-names.mjs <coding-spec.md> <daemon-go-root>')
  process.exit(2)
}

function collectGoTests(directory, tests = new Set()) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      collectGoTests(file, tests)
    } else if (entry.isFile() && entry.name.endsWith('_test.go')) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(/\bfunc\s+(?:\([^)]*\)\s*)?(Test[A-Z][A-Za-z0-9_]*)\s*\(/g)) {
        tests.add(match[1])
      }
    }
  }
  return tests
}

const goTests = collectGoTests(goRoot)
const missing = []
for (const [index, line] of readFileSync(specPath, 'utf8').split('\n').entries()) {
  const cells = line.split('|').map((cell) => cell.trim())
  if (!/^`?COD-T-\d{3,}`?$/.test(cells[1] ?? '')) {
    continue
  }
  const names = [...new Set((cells[4] ?? '').match(/\bTest[A-Z][A-Za-z0-9_]*\b/g) ?? [])]
  for (const name of names) {
    if (!goTests.has(name)) {
      missing.push(`line ${index + 1}: ${cells[1]} references missing Go test ${name}`)
    }
  }
}
for (const problem of missing) {
  console.error(problem)
}
console.log(`Go test names checked: ${goTests.size}; missing references: ${missing.length}`)
process.exitCode = missing.length === 0 ? 0 : 1
