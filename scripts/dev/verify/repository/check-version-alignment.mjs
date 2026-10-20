#!/usr/bin/env node
/**
 * Verify that the repository version is aligned for a release: the root POM `<version>`, its
 * `kk-studio.version` property, the daemon package version and the release tag (`RELEASE_TAG`,
 * optionally `v`-prefixed) must be exactly equal. No version is hard-coded here, so bumping the
 * version never requires editing this script or the workflow that runs it.
 *
 * Usage (from the repository root): `RELEASE_TAG=v1.2.3 node scripts/dev/verify/repository/check-version-alignment.mjs`
 */

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import { repositoryRoot } from '../../lib/repo-root.mjs'

/** The text of the first `<name>` element, or throws when it is absent or blank. */
function requiredText(xml, name, label) {
  const match = new RegExp(`<${name.replace('.', '\\.')}>([^<]*)</${name.replace('.', '\\.')}>`).exec(xml)
  const value = match?.[1].trim()
  if (!value) {
    throw new Error(`cannot read ${label} from pom.xml`)
  }
  return value
}

/** Returns `{ expected, mismatches }`; `mismatches` is empty when every source equals the root version. */
export function checkAlignment({ pomXml, packageJson, tag }) {
  // The project's own coordinates come before its dependencies; the parent block carries a different version.
  const withoutParent = pomXml.replace(/<!--[\s\S]*?-->/g, '').replace(/<parent>[\s\S]*?<\/parent>/, '')
  const expected = requiredText(withoutParent, 'version', 'the root <version>')
  const properties = /<properties>([\s\S]*?)<\/properties>/.exec(withoutParent)?.[1] ?? ''
  const declared = requiredText(properties, 'kk-studio.version', 'the kk-studio.version property')
  const daemonVersion = JSON.parse(packageJson).version
  if (typeof daemonVersion !== 'string' || daemonVersion.trim() === '') {
    throw new Error('cannot read the daemon package version')
  }
  const tagVersion = tag.startsWith('v') ? tag.slice(1) : tag
  const sources = {
    'pom.xml <version>': expected,
    'pom.xml kk-studio.version': declared,
    'daemon/package.json version': daemonVersion.trim(),
    [`release tag ${JSON.stringify(tag)}`]: tagVersion,
  }
  const mismatches = Object.entries(sources)
    .filter(([, value]) => value !== expected)
    .map(([label, value]) => `${label} is ${JSON.stringify(value)}, expected ${JSON.stringify(expected)}`)
  return { expected, mismatches }
}

export function main(root = repositoryRoot(), tag = process.env.RELEASE_TAG ?? '') {
  let result
  try {
    result = checkAlignment({
      pomXml: readFileSync(path.join(root, 'pom.xml'), 'utf8'),
      packageJson: readFileSync(path.join(root, 'daemon/package.json'), 'utf8'),
      tag,
    })
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    return 1
  }
  if (result.mismatches.length > 0) {
    process.stderr.write(`repository version alignment failed:\n${result.mismatches.map((line) => `- ${line}\n`).join('')}`)
    return 1
  }
  process.stdout.write(
    `repository version ${result.expected} is aligned across pom.xml, kk-studio.version, daemon/package.json and ${tag}\n`,
  )
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
