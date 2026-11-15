// Package transport owns the network session, control-message exchange, IDE data routing,
// and resource transfer clients. Runtime and IDE provide the narrow handlers declared at
// this boundary; transport must not own capability or view lifecycle policy.
package transport
