# Known limitations

Deliberate, documented gaps in the Repofyr 4.0.0 server. Each entry is
tracked as a GitHub issue once the repository is public (see `RELEASING.md`,
post-publish steps); the source comments reference this file.

These are limitations, not defects. None of them is a security
vulnerability in itself - but if you can show one is exploitable beyond
what is described here, please report it privately as described in
`SECURITY.md`.

## Authorization and authentication

1. CareTeam-based SMART authorization is not implemented
   - `SmartAuthorizer` supports `compartment` user-scope handling but not
     `careteam`. Configuring `careteam` for a resource type, or as the `*`
     default, throws `NotImplementedError` when a request for that type
     arrives, so the request fails with an internal error rather than a
     clean rejection
     (`repofyr-core/src/main/scala/io/repofyr/authz/SmartAuthorizer.scala`
     lines 291 and 300).
   - Use `compartment` handling, which restricts a user to the Practitioner
     or RelatedPerson compartments they belong to. If you need the
     care-team restriction
     (`?patient:Patient._has:CareTeam:patient:participant=<user>`), supply
     it through a custom `IAuthorizer` rather than the built-in
     configuration.

2. PATCH is authorized against the stored resource, not the patched result
   - For instance interactions Repofyr checks the *existing* resource
     against the caller's resource filters and FHIRPath content
     constraints, so a caller cannot patch a resource they were never
     allowed to touch. What is not checked is the *outcome*: the patch
     operations themselves are not evaluated, and
     `authorizeAgainstGivenContent` returns `true` unconditionally for
     PATCH
     (`repofyr-core/src/main/scala/io/repofyr/authz/AuthzManager.scala:194`).
   - A caller authorized for a resource can therefore patch it into a state
     that a full UPDATE of the same content would have rejected - for
     example moving `Observation.subject` to a patient outside their
     compartment. Where the post-state matters, disable PATCH for the
     affected resource types in the CapabilityStatement and require UPDATE,
     which is fully checked.

3. `jwt-introspection` token resolution is not implemented
   - `fhir.authorization.token-resolution` accepts `jwt` and
     `introspection`. The third defined value, `jwt-introspection`, has no
     resolver: the
     configurator's match falls through and throws an
     `InitializationException` naming it as an unknown method, so the
     server does not start
     (`repofyr-core/src/main/scala/io/repofyr/authz/AuthzConfigurationManager.scala`
     lines 90-94; the corresponding configuration-check branches at lines
     296-297 are empty for the same reason).
   - Use `jwt` for locally verified signatures, or `introspection` to
     validate every token at the authorization server. If you need both -
     a locally verified JWT whose revocation is then confirmed by
     introspection - supply an `ITokenResolver` through the custom token
     resolver hook.

4. Token endpoint client authentication is `client_secret_basic` only
   - `TokenClient` always authenticates with `ClientSecretBasic`
     (`repofyr-core/src/main/scala/io/repofyr/authz/TokenClient.scala:38`).
     This client is used to obtain the access token for **remote
     auditing**, so the limitation applies when Repofyr sends AuditEvent
     resources to an external audit repository, not to inbound request
     authentication.
   - Register Repofyr's audit client with `client_secret_basic` at your
     authorization server. `client_secret_post`, `client_secret_jwt`, and
     `private_key_jwt` are not available for this client.
   - Note that `token_endpoint_auth_method` for the *introspection* client
     is validated against the authorization server's advertised methods
     separately and does support the JWT-based methods.

5. Authentication for the internal API is not implemented
   - `authenticateForInternalApi` rejects every credential it is given
     (`repofyr-core/src/main/scala/io/repofyr/authz/AuthManager.scala:57`).
     Setting `server.internal.authenticate = true` therefore makes the
     internal API unreachable rather than protected.
   - Leave the setting at its default of `false` and restrict the internal
     API at the network layer - bind it to a private interface, or place it
     behind a reverse proxy or network policy that terminates
     authentication.

## Search and query

6. Reference search modifiers `:above` and `:below`
   - Over Reference elements neither `:above` nor `:below` is supported.
     Over canonical elements `:below` works and `:above` does not
     (`repofyr-core/src/main/scala/io/repofyr/db/ReferenceQueryBuilder.scala`
     lines 17 and 22). The supported Reference modifiers are `:identifier`,
     `:[type]`, and the onFHIR-specific `:type`.
   - Enumerate the reference targets you want and pass them as a
     comma-separated OR list, or model the hierarchy explicitly with a
     search parameter you can query directly.

7. `:iterate` is not handled for `_revinclude`
   - `_include:iterate` is implemented and recurses correctly.
     `_revinclude:iterate` is not: reverse includes run for a single level
     against the matched resources only
     (`repofyr-core/src/main/scala/io/repofyr/db/ResourceManager.scala:591`).
     The parameter is accepted rather than rejected, so the response is a
     valid Bundle that is simply missing the deeper levels.
   - Issue one search per level and follow the references client-side, or
     restructure the query so the recursion runs through `_include`, which
     does iterate.

8. Accent, diacritic, and punctuation insensitivity for string search
   - FHIR specifies that a string search without a modifier, and with
     `:contains`, ignores case *and* accents, other diacritical marks,
     punctuation, and non-significant whitespace. Repofyr implements the
     case-insensitive part with a MongoDB regex and nothing else
     (`repofyr-core/src/main/scala/io/repofyr/db/StringQueryBuilder.scala:68`).
     A search term spelled without accents will not match a stored name
     that carries them, and `OBrien` will not match `O'Brien`.
   - Normalize on the way in - store a folded form in an extension or a
     dedicated element and index a search parameter over it - or apply a
     MongoDB collation at the database level for the affected collections.

9. String search runs on `string`, `HumanName`, and `Address` only
   - FHIR allows string search against the text fields of any complex type.
     Repofyr supports the three above and throws
     `InvalidParameterException` for any other target type, so the request
     is rejected with an OperationOutcome rather than returning wrong
     results
     (`repofyr-core/src/main/scala/io/repofyr/db/StringQueryBuilder.scala`
     lines 23-35).
   - Define a custom search parameter of type `string` whose path targets
     the specific text element you need.

10. Token `:in` and `:not-in` require an enumerated ValueSet by canonical URL
    - Both modifiers resolve the ValueSet from server configuration by
      canonical URL. A literal reference to a ValueSet resource is not
      supported, and neither is a ValueSet defined by rules rather than an
      explicit list of codes
      (`repofyr-core/src/main/scala/io/repofyr/db/TokenQueryBuilder.scala`
      lines 20-22). `:below` on a token works only for code systems whose
      hierarchy is syntactic, such as ATC or ICD-10, because it is
      implemented as a prefix query.
    - Supply the ValueSet to the server configuration with its codes
      enumerated, or expand it against a terminology service and pass the
      resulting codes as a comma-separated OR list.

11. Cursor-based pagination cannot be sorted
    - With `fhir.default.pagination = offset`, or whenever `_searchafter`
      or `_searchbefore` is supplied, the cursor is the MongoDB document id
      and no other cursor field is implemented. Combining it with `_sort`
      throws, as does using it for a multi-type search or for history
      (`repofyr-core/src/main/scala/io/repofyr/db/DocumentManager.scala`
      lines 237 and 246;
      `repofyr-core/src/main/scala/io/repofyr/db/ResourceManager.scala`
      lines 138 and 1534).
    - Use cursor pagination for unsorted deep traversal of a single
      resource type, and page-based pagination (`_page`, the default) when
      you need `_sort`, a multi-type search, or history.

## Reference resolution and validation

12. References to a remote FHIR server are not resolved
    - When a literal reference names a server other than this one,
      `ReferenceResolver` returns "not found" without attempting to fetch
      it: `getResource` yields `None` and `isResourceExist` yields `false`
      (`repofyr-core/src/main/scala/io/repofyr/api/validation/ReferenceResolver.scala`
      lines 37 and 107). Only the local repository is consulted. If your
      profiles require referenced resources to exist, an otherwise valid
      resource carrying an absolute external reference is reported as
      having an unresolvable reference.
    - Keep referenced resources in the local repository, or contain them in
      the submitted resource - both resolve normally. If you must accept
      external references, relax reference-existence checking for the
      affected profiles, or supply your own `IReferenceResolver` that
      fetches them.

## Persistence and packaging

13. Sharding uses a single key, on `_id` or a reference parameter
    - Only the first entry of the configured `shardKey` is used; a compound
      shard key silently becomes a single-key one
      (`repofyr-core/src/main/scala/io/repofyr/db/MongoDBInitializer.scala:387`).
      The key must also be either `Resource.id` or a search parameter of
      type `reference`; anything else logs a warning and leaves the
      collection unsharded.
    - Choose the single reference parameter that best distributes the
      collection - commonly the patient or subject reference - and verify
      after startup that the collection was actually sharded rather than
      only warned about.

## FHIR operations

14. `$meta-delete` fails on a resource whose meta has no `security` array
    - `MetaOperationHandler` diffs the submitted Meta against the stored one and then
      casts each of the `profile`, `security` and `tag` results to a JSON array
      (`repofyr-operations/src/main/scala/io/repofyr/operation/MetaOperationHandler.scala`
      lines 145-146). When a category is absent from the diff the value is
      `JNothing`, the cast throws `ClassCastException`, and the request fails
      with HTTP 500 and no OperationOutcome. Deleting a tag from a resource that
      carries no security label - the common case - hits this.
    - Remove meta entries with a normal UPDATE of the resource instead. `$meta`
      and `$meta-add` are unaffected and are covered by
      `FHIRMetaOperationTest`; the missing `$meta-delete` case belongs with the
      fix rather than pinning the broken behavior.
