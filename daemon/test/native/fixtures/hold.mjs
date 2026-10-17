import { holdForever, recordPid } from './_util.mjs'

await recordPid(process.argv[2])
holdForever()
