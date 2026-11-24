## ADDED Requirements

### Requirement: Outbound HTTPS trusts user-installed CAs

All of the App's outbound HTTPS connections (model APIs, web search, downloads, built-in browser, MCP, etc.) MUST trust, in addition to system certificates, CA certificates the user has installed in system settings, so that self-signed endpoints on a corporate intranet (OpenAI-compatible services issued by an internal CA) can be reached directly without root. The cleartext http permission stays as it is.

#### Scenario: connecting to an endpoint issued by an internal CA
- **WHEN** the user has installed the corporate internal CA in system settings and configures a self-signed HTTPS OpenAI-compatible endpoint as a provider
- **THEN** model connections and chat send/receive work normally, with no certificate validation failure

#### Scenario: a device without a user CA installed
- **WHEN** no user-installed CA exists on the device
- **THEN** all outbound HTTPS behavior is exactly the same as before the change (public endpoints trusting only system certificates still connect as before, and self-signed endpoints still fail as before)

#### Scenario: public endpoints are unaffected
- **WHEN** connecting to a normal endpoint issued by a public CA such as Let's Encrypt
- **THEN** certificate validation passes as before, with no extra prompt or downgrade
