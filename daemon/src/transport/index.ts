export {
  DaemonWebSocketTransport,
  createDaemonTransport,
  type ControlConnection,
  type ControlTransport,
  type ControlTransportListener,
  type DaemonTransportOptions,
} from './daemon-transport.js'

export {
  handleIdeConnectRequest,
  handleIdeHttpRequest,
  validateUpstream,
} from './ide-tunnel.js'

export {
  createHttpPutObject,
  createResourceTransferClient,
  type PresignedPutSender,
  type ResourceControlSender,
  type ResourceTransferClient,
  type ResourceTransferClientOptions,
} from './resources.js'
