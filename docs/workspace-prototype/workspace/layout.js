export const MIN_W = 320;
export const MIN_H = 240;
export const DIV = 1;
export const MAX_PANES = 8;

export function createLeaf(id) {
  return { t: 'leaf', id };
}

function createSplit(id, dir, ratio, first, second) {
  return { t: 'split', id, dir, ratio, a: first, b: second };
}

export function leaves(node) {
  return node.t === 'leaf' ? [node] : [...leaves(node.a), ...leaves(node.b)];
}

function findParent(node, child) {
  if (node.t === 'leaf') return null;
  if (node.a === child || node.b === child) return node;
  return findParent(node.a, child) ?? findParent(node.b, child);
}

export function findSplit(node, id) {
  if (node.t === 'leaf') return null;
  return node.id === id ? node : findSplit(node.a, id) ?? findSplit(node.b, id);
}

function replaceLayoutNode(root, oldNode, newNode) {
  if (root === oldNode) return newNode;
  const parent = findParent(root, oldNode);
  if (parent.a === oldNode) parent.a = newNode;
  else parent.b = newNode;
  return root;
}

export function splitPane(layout, leaf, dir, splitId, paneId) {
  const next = createLeaf(paneId);
  layout.root = replaceLayoutNode(layout.root, leaf, createSplit(splitId, dir, 0.5, leaf, next));
  layout.focus = next.id;
}

export function closePane(layout, leaf) {
  const parent = findParent(layout.root, leaf);
  const sibling = leaf === parent.a ? parent.b : parent.a;
  layout.root = replaceLayoutNode(layout.root, parent, sibling);
  if (layout.focus === leaf.id) layout.focus = leaves(sibling)[0].id;
}

export function swapPanes(layout, from, to) {
  const fromParent = findParent(layout.root, from);
  const toParent = findParent(layout.root, to);
  const fromSlot = fromParent.a === from ? 'a' : 'b';
  const toSlot = toParent.a === to ? 'a' : 'b';
  fromParent[fromSlot] = to;
  toParent[toSlot] = from;
  layout.focus = from.id;
}

export function paneLetter(order, leaf) {
  return String.fromCharCode(65 + order.indexOf(leaf));
}

export function minWidth(node) {
  if (node.t === 'leaf') return MIN_W;
  const a = minWidth(node.a), b = minWidth(node.b);
  return node.dir === 'row' ? a + b + DIV : Math.max(a, b);
}

export function minHeight(node) {
  if (node.t === 'leaf') return MIN_H;
  const a = minHeight(node.a), b = minHeight(node.b);
  return node.dir === 'col' ? a + b + DIV : Math.max(a, b);
}

export function treeKey(node) {
  return node.t === 'leaf' ? node.id : `${node.id}(${treeKey(node.a)},${treeKey(node.b)})`;
}
