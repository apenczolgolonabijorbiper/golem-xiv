# Delegated final-text Cognizer (proposal)

Optional JVM module implementing the existing `Cognizer` interface. A host application
supplies a cancellable `DelegatedTextTransport`, a `CognitionRepository`, its self-agent
ID and a vendor label. This is useful for asynchronous operator-assisted or external
text providers. There is no dependency on a particular provider, hosted app or queue.

The transport receives exact serialized request JSON and its SHA-256 digest. It returns
`DelegatedTextResponse`; JSON transports must decode using `protocolJson` to reject
unknown fields. Transport authentication, delivery, access control, replay protection
at the queue, durable acknowledgements and cleanup are host responsibilities. The
Cognizer checks correlation, digest, time bounds and output size before writing. A
cooperatively cancellable transport is required. Digests bind bytes, not identity;
use an authenticated transport. Model and vendor labels are declared metadata, not
cryptographic attestation.

Response text is persisted through the normal repository methods and emitted through
the existing expression/text events. No Intent, code execution, autonomous tool use,
streaming generation or automatic fallback is supported. Constitution and phenomenal
history are inputs; this module does not create a persona, semantic memory or biography.
Repository failures can leave a partial expression, as in existing Cognizers. Retrying
collection creates a new request; exactly-once execution is not promised.

The server's default provider, authentication and configuration are unchanged. Compose
this Cognizer through the existing `GolemXiv` dependency injection in the host. Before
adding a default server factory or a named provider, discuss configuration with the
maintainers separately.

Validation: `./gradlew :golem-xiv-cognizer-delegated:test` (JDK 21).
Tests use synthetic data, an in-memory recording repository and cancellable transports;
no external account or model key is required. Integration against a real graph and
actual hosted provider remains the host application's responsibility.
