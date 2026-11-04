import path from 'node:path'
import { create } from '@bufbuild/protobuf'
import {
  SkillPackageStateSchema,
  type SkillPackage,
  type SkillPackageState,
} from '../generated/control_pb.js'
import type { SkillPackageInstaller } from './installer.js'

export type SkillStateReporter = (states: SkillPackageState[]) => Promise<void> | void

export interface SkillReconciler {
  onDesiredSet(packages: SkillPackage[]): void
}

export function createSkillReconciler(
  installer: SkillPackageInstaller,
  report: SkillStateReporter,
): SkillReconciler {
  let isRunning = false
  let pendingDesiredSet: Map<string, SkillPackage> | null = null

  async function runLoop(): Promise<void> {
    if (isRunning) {
      return
    }
    isRunning = true
    try {
      while (pendingDesiredSet !== null) {
        const desiredMap = pendingDesiredSet
        pendingDesiredSet = null
        await executeRound(desiredMap)
      }
    } finally {
      isRunning = false
      if (pendingDesiredSet !== null) {
        runLoop().catch(logDiagnostic)
      }
    }
  }

  async function executeRound(desiredMap: Map<string, SkillPackage>): Promise<void> {
    const results = new Map<string, SkillPackageState>()

    // 1. Process desired packages serially
    for (const [packageName, pkg] of desiredMap) {
      const targetSha = pkg.sha256?.toLowerCase() ?? ''
      const currentMarker = await installer.readMarker(packageName)
      if (currentMarker !== null && targetSha.length === 64 && currentMarker === targetSha) {
        // Skip install, marker matches
        results.set(
          packageName,
          create(SkillPackageStateSchema, {
            packageName,
            installedSha256: currentMarker,
            localPath: path.join(installer.skillsRoot(), packageName),
          }),
        )
      } else {
        const sizeBytes = pkg.sizeBytes ?? 0n
        const downloadUrl = pkg.downloadUrl ?? ''
        const state = await installer.install(packageName, targetSha, sizeBytes, downloadUrl)
        results.set(packageName, state)
      }
    }

    // 2. Delete installed roots that carry a marker but are not in the desired set
    try {
      const installed = await installer.listInstalledPackages()
      for (const item of installed) {
        if (!desiredMap.has(item.packageName)) {
          try {
            await installer.remove(item.packageName)
          } catch (removeErr) {
            logDiagnostic(removeErr)
          }
        }
      }
    } catch (listErr) {
      logDiagnostic(listErr)
    }

    // 3. Report ALL managed packages (desired ∪ still-present marker directories)
    try {
      const stillPresent = await installer.listInstalledPackages()
      for (const item of stillPresent) {
        if (!results.has(item.packageName)) {
          results.set(
            item.packageName,
            create(SkillPackageStateSchema, {
              packageName: item.packageName,
              installedSha256: item.installedSha256,
              localPath: item.localPath,
            }),
          )
        }
      }
    } catch (listErr) {
      logDiagnostic(listErr)
    }

    const reportStates = Array.from(results.values())
    try {
      await report(reportStates)
    } catch (reportError) {
      logDiagnostic(reportError)
    }
  }

  function logDiagnostic(error: unknown): void {
    const message = error instanceof Error ? error.message : String(error)
    process.stderr.write(`daemon skill reconcile report failed: ${message}\n`)
  }

  return {
    onDesiredSet(packages: SkillPackage[]): void {
      const desiredMap = new Map<string, SkillPackage>()
      for (const pkg of packages) {
        if (pkg.packageName) {
          desiredMap.set(pkg.packageName, pkg)
        }
      }
      pendingDesiredSet = desiredMap
      runLoop().catch(logDiagnostic)
    },
  }
}
