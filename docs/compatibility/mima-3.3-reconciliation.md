# MiMa 3.3 Compatibility Reconciliation

The accepted machine baseline compares the current reusable JARs with the
public `3.3` artifacts. Version `4.0.0` is intentionally a major release. This
table groups every reported issue family and connects it to migration guidance
in the split plan rather than suppressing individual class findings.

| MiMa report group | Intended change / migration-table reference |
|---|---|
| Common FHIR media/content constants | Akka media/content types replaced by `FhirMediaType` / `FhirContentType` (section 7.2, Phase 2B) |
| `io.onfhir.api.client.*` missing from Common | Classes moved to `onfhir-client_2.13` with packages unchanged (section 7.1, Phase 1A) |
| neutral request/response/status/date/URI signatures | Akka HTTP types replaced by neutral/JDK models (section 7.2, Phase 2B) |
| bundle parsing methods | explicit endpoint settings, JDK URI, and library-safe exceptions (section 7.2, Phases 1C, 2B, 3.5) |
| query parsers/resolvers and in-memory query helpers missing from Common | moved to `onfhir-query_2.13` (section 7.1, Phases 1D and 3.6) |
| `FHIRUtil`, search parser, and foundation parser signatures | injected endpoint/capability/search defaults and neutral date/status types (section 7.2, Phases 1C and 2B) |
| Common server auth, audit, DB, event, exception, validation-strategy, and configuration types | moved to Core or Event (section 7.1, Phases 1D and 3.5) |
| `SubscriptionUtil` missing from Common | release-specific strategy obtained through the server configurator (section 7.1/7.2, Phase 3.5) |
| client transport/interceptor/marshaller signatures and former case-class surface | JDK transport contract and explicit factories (section 7.2, Phase 3) |
| Path terminology function constructor | terminology integration now follows the injected service contract used by the transport-neutral library boundary |
| Config `BaseFhirServerConfigurator` missing | server configurator moved to Core (section 7.1, Phase 1D) |
| Validation and R4 parser constructor/parse-element signatures | endpoint/capability defaults are explicit and structure parsers carry element metadata (section 7.2, Phase 1C) |
| `AuthzContext`, `AuthzResult`, `OperationConf`, `OperationParamDef`, and `ElementRestrictions` signatures | pre-split 3.3-to-4.0 model evolution retained on the approved release line; consumers must recompile and use JSON-valued auth context parameters, `AuthzConstraints`, level-aware operation parameters, and string profile provenance |
| Query and Template artifacts | new artifacts with no public `3.3` baseline |
| Expression | binary compatible with `3.3` |

The raw accepted findings are in `mima-3.3-accepted.txt`. CI regenerates the
report and fails on any difference. Updating the baseline requires reviewing
this reconciliation and the main migration table in the same change.
