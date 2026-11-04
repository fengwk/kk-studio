/** 创建只向指定数组添加 case 的函数，并统一规范化 requires。 */
export function createCaseAdder(cases) {
  return (def) => cases.push({ ...def, requires: new Set(def.requires || []) })
}

/** 按 ID 在指定的 case 数组中查找。 */
export function findCase(cases, id) {
  const c = cases.find((x) => x.id === id)
  if (!c) throw new Error(`unknown case: ${id}`)
  return c
}
