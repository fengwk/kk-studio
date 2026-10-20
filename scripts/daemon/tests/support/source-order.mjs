/** Helpers for tests that pin the source-level contracts of shipped script text. */

import assert from 'node:assert/strict'

/** Assert that safety-sensitive operations occur in the required source order. */
export function assertInOrder(body, ...needles) {
  let previous = -1
  for (const needle of needles) {
    const current = body.indexOf(needle, previous + 1)
    assert.ok(current > previous, `${JSON.stringify(needle)} is missing or out of order`)
    previous = current
  }
}

/** Source text with `#` comment lines removed, for "this code never does X" assertions. */
export const withoutComments = (text) =>
  text
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('#'))
    .join('\n')

export function assertIncludesAll(text, needles, context = '') {
  for (const needle of needles) {
    assert.ok(text.includes(needle), `${context}${context ? ': ' : ''}${JSON.stringify(needle)} is missing`)
  }
}

export function assertIncludesNone(text, needles, context = '') {
  for (const needle of needles) {
    assert.ok(!text.includes(needle), `${context}${context ? ': ' : ''}${JSON.stringify(needle)} must not appear`)
  }
}
