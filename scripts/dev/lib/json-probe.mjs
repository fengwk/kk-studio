/** Python-style scalar rendering used by the shell probes. */
export const render = (value) => (value === null || value === undefined ? 'None' : value === true ? 'True' : value === false ? 'False' : String(value))

export function runProbe(commands) {
  const [command, ...args] = process.argv.slice(2)
  if (!(command in commands)) {
    process.stderr.write(`unknown probe: ${command}\n`)
    process.exit(2)
  }
  try {
    commands[command](...args)
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  }
}
