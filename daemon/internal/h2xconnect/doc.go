// Package h2xconnect appends http2xconnect=1 to GODEBUG for the processes this daemon starts.
//
// Go 1.27 only lets its HTTP/2 server accept extended CONNECT requests, which the control channel
// needs when the platform tunnels an IDE WebSocket, when GODEBUG already contains http2xconnect=1
// at process startup. The executable therefore re-executes itself with that value; this package's
// init keeps the setting in the environment inherited by the helper processes it starts later.
package h2xconnect
