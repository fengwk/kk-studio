/** case 注册表。 */

/** @type {Array<{id:string,level:string,title:string,requires:Set<string>,docs:string,run:Function}>} */
export const ALL_CASES = []

export function registerCase(def) {
  ALL_CASES.push({
    ...def,
    requires: new Set(def.requires || []),
  })
  return def.run
}

export function getCase(id) {
  const c = ALL_CASES.find((x) => x.id === id)
  if (!c) throw new Error(`unknown case: ${id}`)
  return c
}
