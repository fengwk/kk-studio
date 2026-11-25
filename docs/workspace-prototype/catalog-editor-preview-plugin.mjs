// Offline data source for the real pricing editor. Preserve decimal strings.
export async function previewModelPricing({ pricing, inputTokens, requestedAt, serviceTier }, signal) {
  signal.throwIfAborted()
  const empty = { rates: null, activeFrom: null, matchedRules: [] }
  if (!pricing || (pricing.changes.length && requestedAt === undefined)) return empty
  const date = requestedAt === undefined ? '' : new Date(requestedAt).toISOString().slice(0, 10)
  const active = pricing.changes.filter((change) => change.from <= date).at(-1)
  const { base, rules } = active ?? pricing
  const rates = { ...base }
  const matchedRules = []
  const fields = ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning']
  const clockMinutes = (value) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3))
  for (const rule of rules) {
    let matched = false
    switch (rule.type) {
      case 'input_token_range':
        matched = inputTokens >= (rule.minInputTokens ?? 0) && inputTokens < (rule.maxInputTokensExclusive ?? Infinity)
        if (matched) {
          for (const field of fields) if (rule[field] !== undefined) rates[field] = rule[field]
          if (rule.cacheWriteByTtl !== undefined) rates.cacheWriteByTtl = rule.cacheWriteByTtl
        }
        break
      case 'service_tier_multiplier':
        matched = serviceTier === rule.serviceTier
        break
      case 'time_multiplier': {
        if (requestedAt === undefined) break
        const parts = new Intl.DateTimeFormat('en-GB', {
          timeZone: rule.timezone, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
        }).formatToParts(requestedAt)
        const part = (name) => parts.find((item) => item.type === name)?.value
        const minutes = Number(part('hour')) * 60 + Number(part('minute'))
        const day = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(part('weekday')) + 1
        matched = minutes >= clockMinutes(rule.start) && minutes < clockMinutes(rule.end)
          && (!rule.weekdays?.length || rule.weekdays.includes(day))
        break
      }
    }
    if (matched) matchedRules.push(rule)
  }
  const activeFrom = active?.from ?? null
  if (rules.some((rule) => (rule.type === 'time_multiplier' && requestedAt === undefined)
    || (rule.type === 'service_tier_multiplier' && !serviceTier))) {
    return { rates: null, activeFrom, matchedRules }
  }

  function multiply(value, factor) {
    const places = (text) => text.split('.')[1]?.length ?? 0
    const scale = places(value) + places(factor)
    const product = String(BigInt(value.replace('.', '')) * BigInt(factor.replace('.', '')))
    if (!scale) return product
    const digits = product.padStart(scale + 1, '0')
    return (digits.slice(0, -scale) + '.' + digits.slice(-scale)).replace(/0+$/, '').replace(/\.$/, '')
  }
  for (const type of ['service_tier_multiplier', 'time_multiplier']) {
    const rule = matchedRules.find((rule) => rule.type === type)
    if (!rule) continue
    for (const field of fields) {
      if (rates[field] != null) rates[field] = multiply(rates[field], rule[field + 'Factor'] ?? rule.factor ?? '1')
    }
    rates.cacheWriteByTtl = Object.fromEntries(Object.entries(rates.cacheWriteByTtl).map(([ttl, rate]) => [
      ttl, multiply(rate, rule.cacheWriteFactor ?? rule.factor ?? '1'),
    ]))
  }
  return { rates, activeFrom, matchedRules }
}
