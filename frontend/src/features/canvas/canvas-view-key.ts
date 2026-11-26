/** Keep the original local storage namespace for the primary view. */
export function canvasViewKey(canvasId: string, viewId?: string): string {
  return viewId === undefined ? canvasId : `${canvasId}/${viewId}`
}
