#!/usr/bin/env node
/**
 * Verify that the repository version is aligned: the root POM `<version>`, its
 * `kk-studio.version` property, the Go daemon version, the daemon IDE extension package version,
 * the frontend package version and, when `RELEASE_TAG` is provided, the release tag must match.
 * No version is hard-coded here, so bumping the version never requires editing this script or the
 * workflow that runs it.
 *
 * Absent `RELEASE_TAG` checks only the repository's internal versions. An explicitly supplied tag,
 * including a blank or otherwise invalid value, is always checked and never ignored.
 *
 * Usage (from the repository root): `node scripts/dev/verify/repository/check-version-alignment.mjs`
 * Release: `RELEASE_TAG=v1.2.3 node scripts/dev/verify/repository/check-version-alignment.mjs`
 */

import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import { repositoryRoot } from '../../lib/repo-root.mjs'

const GO_DAEMON_VERSION_DECLARATION = /^var DaemonVersion\s*=\s*"([^"\r\n]+)"\s*$/gm

/** The text of the first `<name>` element, or throws when it is absent or blank. */
function requiredText(xml, name, label) {
  const match = new RegExp(`<${name.replace('.', '\\.')}>([^<]*)</${name.replace('.', '\\.')}>`).exec(xml)
  const value = match?.[1].trim()
  if (!value) {
    throw new Error(`cannot read ${label} from pom.xml`)
  }
  return value
}

/** 版本常量必须恰好声明一次字面量；缺失、重复或非字面量都无法可靠判定，直接失败。 */
function requiredGoDaemonVersion(source) {
  const matches = [...source.matchAll(GO_DAEMON_VERSION_DECLARATION)]
  if (matches.length !== 1) {
    throw new Error('cannot read exactly one literal DaemonVersion declaration from daemon-go/internal/config/version.go')
  }
  return matches[0][1]
}

/** 返回 `{ expected, mismatches }`；未提供 tag 时只比较仓库内部版本。 */
export function checkAlignment({ pomXml, ideExtensionPackageJson, frontendPackageJson, goConfig, tag }) {
  // The project's own coordinates come before its dependencies; the parent block carries a different version.
  const withoutParent = pomXml.replace(/<!--[\s\S]*?-->/g, '').replace(/<parent>[\s\S]*?<\/parent>/, '')
  const expected = requiredText(withoutParent, 'version', 'the root <version>')
  const properties = /<properties>([\s\S]*?)<\/properties>/.exec(withoutParent)?.[1] ?? ''
  const declared = requiredText(properties, 'kk-studio.version', 'the kk-studio.version property')
  const sources = {
    'pom.xml <version>': expected,
    'pom.xml kk-studio.version': declared,
  }
  if (ideExtensionPackageJson !== undefined) {
    const ideExtensionVersion = JSON.parse(ideExtensionPackageJson).version
    if (typeof ideExtensionVersion !== 'string' || ideExtensionVersion.trim() === '') {
      throw new Error('cannot read the daemon IDE extension package version')
    }
    sources['daemon-go/ide-extension/package.json version'] = ideExtensionVersion.trim()
  }
  if (frontendPackageJson !== undefined) {
    const frontendVersion = JSON.parse(frontendPackageJson).version
    if (typeof frontendVersion !== 'string' || frontendVersion.trim() === '') {
      throw new Error('cannot read the frontend package version')
    }
    sources['frontend/package.json version'] = frontendVersion.trim()
  }
  if (goConfig === undefined) {
    throw new Error('cannot read Go daemon version source')
  }
  sources['daemon-go/internal/config/version.go DaemonVersion'] = requiredGoDaemonVersion(goConfig)
  if (tag !== undefined) {
    if (typeof tag !== 'string' || !/^v[0-9A-Za-z._+-]+$/.test(tag) || tag === 'v') {
      throw new Error(`invalid RELEASE_TAG ${JSON.stringify(tag)}`)
    }
    const tagVersion = tag.slice(1)
    sources[`release tag ${JSON.stringify(tag)}`] = tagVersion
  }
  const mismatches = Object.entries(sources)
    .filter(([, value]) => value !== expected)
    .map(([label, value]) => `${label} is ${JSON.stringify(value)}, expected ${JSON.stringify(expected)}`)
  return { expected, mismatches }
}

export function main(root = repositoryRoot(), tag = process.env.RELEASE_TAG) {
  let result
  let ideExtensionChecked = false
  let frontendChecked = false
  try {
    const ideExtensionPath = path.join(root, 'daemon-go/ide-extension/package.json')
    const ideExtensionPackageJson = existsSync(ideExtensionPath)
      ? readFileSync(ideExtensionPath, 'utf8')
      : undefined
    ideExtensionChecked = ideExtensionPackageJson !== undefined
    const frontendPath = path.join(root, 'frontend/package.json')
    const frontendPackageJson = existsSync(frontendPath)
      ? readFileSync(frontendPath, 'utf8')
      : undefined
    frontendChecked = frontendPackageJson !== undefined
    const goDaemonPath = path.join(root, 'daemon-go/internal/config/version.go')
    const goConfig = readFileSync(goDaemonPath, 'utf8')
    result = checkAlignment({
      pomXml: readFileSync(path.join(root, 'pom.xml'), 'utf8'),
      ideExtensionPackageJson,
      frontendPackageJson,
      goConfig,
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
  const sourceList = [
    'pom.xml',
    'kk-studio.version',
    'daemon-go/internal/config/version.go',
    ...(ideExtensionChecked ? ['daemon-go/ide-extension/package.json'] : []),
    ...(frontendChecked ? ['frontend/package.json'] : []),
  ]
  const sources = `${sourceList.slice(0, -1).join(', ')} and ${sourceList[sourceList.length - 1]}`
  const tagSuffix = tag === undefined ? '' : ` and release tag ${tag}`
  process.stdout.write(`repository version ${result.expected} is aligned across ${sources}${tagSuffix}\n`)
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
