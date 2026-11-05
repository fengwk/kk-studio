/**
 * 等待指定的毫秒数。
 */
export function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}
