# Protocols — connectors

Rules specific to the casehub-connectors repo.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [shared-http-client.md](shared-http-client.md) | Use HttpHelper.CLIENT, not new HttpClient instances | Any class making outbound HTTP calls |
| [inbound-connector-id-constants.md](inbound-connector-id-constants.md) | Connector IDs are constants in InboundConnectorIds, not strings | Connector implementations and downstream routing code |
| [spi-id-method-naming.md](spi-id-method-naming.md) | SPI identifier methods are named `id()`, not `connectorId()` or `typeId()` | Any new SPI interface added to core |
| [mcp-tool-blocking-annotation.md](mcp-tool-blocking-annotation.md) | `@Blocking` required on every `@Tool` method calling blocking HTTP | casehub-connectors-mcp, all `@Tool` methods |
| [credential-config-ownership.md](credential-config-ownership.md) | Credential config properties belong to callers, not shared HTTP clients | Shared HTTP clients used by multiple consumers |
