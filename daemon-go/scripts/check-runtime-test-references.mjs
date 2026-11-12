#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const [specPath] = process.argv.slice(2)
if (!specPath) {
  console.error('usage: node check-runtime-test-references.mjs <runtime-spec.md>')
  process.exit(2)
}

const moduleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const testFunctions = new Set()

function collectTests(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      collectTests(absolute)
      continue
    }
    if (!entry.isFile() || !entry.name.endsWith('_test.go')) {
      continue
    }
    const relative = path.relative(moduleRoot, absolute).replaceAll(path.sep, '/')
    const packagePath = path.posix.dirname(relative)
    const source = fs.readFileSync(absolute, 'utf8')
    for (const match of source.matchAll(/func\s+(Test[A-Za-z0-9_]*)\s*\(/g)) {
      testFunctions.add(`${packagePath}:${match[1]}`)
    }
  }
}

collectTests(moduleRoot)
const spec = fs.readFileSync(path.resolve(specPath), 'utf8')
const problems = []
let references = 0
for (const [index, line] of spec.split(/\r?\n/).entries()) {
  if (!line.includes('RUN-T-')) {
    continue
  }
  const cells = line.split('|')
  for (const match of cells[4]?.matchAll(/`((?:internal|cmd)\/[A-Za-z0-9_./-]+:Test[A-Za-z0-9_]*)`/g) ?? []) {
    references++
    if (!testFunctions.has(match[1])) {
      problems.push(`line ${index + 1}: missing Go test ${match[1]}`)
    }
  }
}
if (references === 0) {
  problems.push('no explicit Go test references found; use `internal/package:TestName` in the Go test column')
}
if (problems.length > 0) {
  console.error(problems.join('\n'))
  process.exit(1)
}
console.log(`Go test references matched: ${references}; problems: 0`)
