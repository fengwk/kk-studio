import type { CatalogVersion } from '@/shared/api/contracts/base'

function catalogVersionValue(version: CatalogVersion): bigint {
  try {
    return BigInt(version)
  } catch {
    return 0n
  }
}

/** 十进制版本比较；非法值按 0 处理，避免精度丢失。 */
export function compareCatalogVersions(
  left: CatalogVersion,
  right: CatalogVersion,
): number {
  const delta = catalogVersionValue(left) - catalogVersionValue(right)
  if (delta < 0n) {
    return -1
  }
  if (delta > 0n) {
    return 1
  }
  return 0
}
