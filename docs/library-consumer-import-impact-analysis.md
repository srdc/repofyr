# Library Consumer Import Impact Analysis

> Status: initial import-level analysis complete 2026-07-31
>
> Sources:
> `docs/spark-on-fhir-imports.txt`, `docs/onfhir-cds-imports.txt`, and
> `docs/ignifyr-imports.txt`
>
> Related implementation plan:
> `docs/plans/library-server-split-plan-v2.md`

## 1. Purpose

This report maps the supplied `io.onfhir.*` import inventories to:

- the class or API family being consumed;
- its current Maven module in this repository;
- its recommended owner after the library/server split;
- the migration risk for spark-on-fhir, onfhir-cds, and ignifyr/toFHIR.

The result is intended to prevent a public API from being deleted or moved into
the GPL/server family solely because it has no in-repository consumer.

## 2. Method And Limitations

The input files are IDE "Find Usages" exports, not source trees or compiler
dependency reports. The numbers below are therefore **import-statement
occurrences**, not runtime calls or complete usage counts.

- Imports belonging to the project itself were excluded from onFHIR module
  counts:
  - `io.onfhir.spark.*` in spark-on-fhir;
  - `io.onfhir.cds.*` in onfhir-cds;
  - `io.onfhir.definitions.*` in the ignifyr/toFHIR inventory.
- The two `io.onfhir.spark.*` imports in ignifyr are retained as an upstream
  spark-on-fhir dependency.
- A braced import can touch more than one current module, especially
  `io.onfhir.config.{...}`. Module-touch totals can therefore be slightly
  higher than direct import-line totals.
- Wildcard imports such as `io.onfhir.path._` can hide additional types.
- Two spark-on-fhir grammar import lines are truncated in the IDE export
  (`IndexInvoca...` and `InvocationContex...`). They are safely attributable to
  `onfhir-path`, but the precise nested parser types should be regenerated from
  a non-truncated source scan before final migration.
- One spark entry is in `codex-binding-support.patch`; it is excluded from the
  compiled-source count.
- Import presence proves a compile-time dependency but does not prove which
  methods are called. High-risk consumers still need source-level build and
  signature verification in their own repositories.

## 3. Inventory Summary

| Consumer inventory | All `io.onfhir` import occurrences | Self imports excluded | Direct imports from this repository | Direct main/unknown | Direct test | Distinct expanded upstream targets |
|---|---:|---:|---:|---:|---:|---:|
| spark-on-fhir | 222 | 95 `io.onfhir.spark.*` | 126 compiled/test + 1 patch | 113 + 1 patch | 13 | 86 |
| onfhir-cds | 77 | 56 `io.onfhir.cds.*` | 21 | 19 | 2 | 19 |
| ignifyr/toFHIR | 184 | 59 `io.onfhir.definitions.*` | 123 onFHIR + 2 spark-on-fhir | 90 onFHIR + 2 spark | 33 | 61 onFHIR + 2 spark |

The dominant consumer is spark-on-fhir, followed closely by ignifyr/toFHIR.
onfhir-cds has fewer imports, but several are high-impact server/client
contracts.

## 4. Current Module Exposure Matrix

Counts are import statements touching the current module. A mixed braced
import may be counted in two rows.

| Current provider | spark-on-fhir | onfhir-cds | ignifyr/toFHIR | Split relevance |
|---|---:|---:|---:|---|
| `onfhir-common` | 81 | 14 | 79 | Largest public surface; contains client/query/server types scheduled for relocation |
| `onfhir-client` | 17 | 2 | 12 | Used by all three; Phase 3 is a cross-project breaking change |
| `onfhir-path` | 26 | 3 | 23 | Used heavily by all three; package/API stability is important |
| `onfhir-config` | 4 | 0 | 1 | Spark has one server-bootstrap dependency mixed with reusable readers |
| `onfhir-validation` | 2 | 0 | 1 | Library-grade and expected to remain stable |
| `onfhir-expression` | 0 | 0 | 3 | Library-grade and expected to remain stable |
| `onfhir-template-engine` | 0 | 0 | 1 | Library-grade and expected to remain stable |
| `onfhir-r4` | 1 | 0 | 2 | `R4Parser` constructor/default changes affect both consumers |
| `onfhir-core` | 0 | 2 | 0 | onfhir-cds intentionally integrates with the server configuration manager |
| `onfhir-server-r4` | 0 | 0 | 1 | ignifyr imports the server configurator |
| `onfhir-server-r5` | 0 | 0 | 1 | ignifyr imports the server configurator |
| spark-on-fhir | n/a | n/a | 2 | ignifyr migration must follow the spark-on-fhir release |

## 5. Cross-Project High-Impact APIs

| API or family | spark | CDS | ignifyr | Current owner | Recommended post-split owner | Required action |
|---|:---:|:---:|:---:|---|---|---|
| `io.onfhir.api.client.*` | yes | yes | yes | `onfhir-common` | `onfhir-client` | Move all 22 files, preserve packages, add/directly verify client dependencies in all consumers |
| `OnFhirNetworkClient`, `FhirClientUtil`, security settings | yes | yes | yes | `onfhir-client` | `onfhir-client` | Adapt ActorSystem-free construction and neutral request/response transport |
| `IHttpRequestInterceptor` | no | yes | no | `onfhir-client` | `onfhir-client` | Migrate CDS interceptor use from Akka `HttpRequest` to `ClientHttpRequest` |
| `FHIRRequest`, `FHIRResponse` | indirect | yes | no | `onfhir-common` | `onfhir-common` | CDS handlers must adapt neutral status/header/date types |
| `DateTimeUtil` | yes | yes | no | `onfhir-common` | `onfhir-common` | Pin `Instant`/HTTP-date behavior before replacing Akka `DateTime` |
| `FHIRSearchParameterValueParser` | yes | no | no | `onfhir-common` | `onfhir-common` | Keep reusable parser; inject request defaults; move only Akka directive adapter to core |
| `FhirQueryParser` | yes | no | no | `onfhir-common` | `onfhir-query` | **Do not delete**; move with package unchanged and replace Akka URI parsing |
| `FHIRResultParameterResolver` | yes | no | no | `onfhir-common` | `onfhir-query` | **Do not move to core**; inject pagination/result defaults and preserve public package |
| `BaseFhirServerConfigurator` | yes | no | no | `onfhir-config` | `onfhir-core` | Refactor `SparkFhirConfigurator` onto library-grade configuration APIs; do not make Spark depend on core |
| `IFhirAuditCreator`, `AuditConfig` | yes | no | no | `onfhir-common` | `onfhir-core` | Remove Spark's audit/bootstrap inheritance requirement or introduce a narrow library SPI only if semantics require it |
| `IFhirConfigurationManager` | no | yes | no | `onfhir-core` | `onfhir-core` | Keep as an explicit CDS-to-Repofyr server integration dependency |
| `FhirR4Configurator`, `FhirR5Configurator` | no | no | yes | server-r4/server-r5 | server-r4/server-r5 | Replace in ignifyr if it must remain library-only; do not move audit/server configurators into Apache libraries |
| `R4Parser`, `IFhirFoundationResourceParser` | yes | no | yes | `onfhir-r4` / common | same library family | Preserve packages; adapt explicit `FhirCapabilityDefaults` construction |
| FHIRPath evaluator/value/grammar APIs | yes | yes | yes | `onfhir-path` | `onfhir-path` | Maintain public compatibility; verify wildcard and generated-parser imports explicitly |
| `FhirExpression`, template handler | no | no | yes | expression/template modules | same | No split relocation expected |
| `FHIRUtil`, `IOUtil`, `JsonFormatter`, constants/models | yes | yes | yes | `onfhir-common` | `onfhir-common` | Keep stable; add explicit settings only where server singleton behavior currently leaks in |

## 6. spark-on-fhir Detailed Usage

### 6.1 Imported API groups

| Provider after recommended split | Imported classes and members | Impact |
|---|---|---|
| `onfhir-common` | `Resource`, `Parameter`, `FHIRUtil`, `DateTimeUtil`, `JsonFormatter`, `ProfileRestrictions`, `IFhirFoundationResourceParser`, `FHIRSearchParameterValueParser`, `FhirServerConfig`, `SearchParameterConf`, `FHIRSearchParameter`, `FHIRCapabilityStatement`, `IFhirConfigReader`, `InvalidParameterException`, and package constants including `FHIR_COMMON_FIELDS`, `FHIR_DATA_TYPES`, `FHIR_INTERACTIONS`, `FHIR_PARAMETER_CATEGORIES`, `FHIR_PARAMETER_TYPES`, `FHIR_PREFIXES_MODIFIERS`, `FHIR_SEARCH_RESULT_PARAMETERS`, `FHIR_SEARCH_SPECIAL_PARAMETERS`, foundation-resource URLs/files, and summary/search path constants | Mostly stable artifact ownership; HTTP/date/default signatures change in Phases 1C-2 |
| `onfhir-client` | `IOnFhirClient`, `FhirSearchRequestBuilder`, `FHIRSearchSetReturningRequestBuilder`, `FHIRSearchSetBundle`, `FhirBatchTransactionRequestBuilder`, `FhirClientException`, `OnFhirNetworkClient`, `FhirClientUtil`, `IFhirRepositorySecuritySettings`, `BasicAuthenticationSettings`, `BearerTokenAuthorizationSettings`, `FixedTokenAuthenticationSettings` | Highest-volume migration; builders relocate here and transport construction changes |
| `onfhir-query` | `FhirQueryParser`, `FHIRResultParameterResolver` | Newly confirmed public consumers; preserve package names and add query dependency |
| `onfhir-path` | `FhirPathEvaluator`, `FhirPathLiteralEvaluator`, `FhirPathEnvironment`, `FhirPathResult`, `FhirPathComplex`, scalar value types, `AbstractFhirPathFunctionLibrary`, `FhirPathValueTransformer`, `FhirPathUtil`, generated lexer/parser visitor classes, and multiple `FhirPathExprParser` context types | Broad source dependency; no package relocation recommended |
| `onfhir-config` | `FSConfigReader`, `FhirApiConfigReader`, `SearchParameterConfigurator` | Remain library-grade; verify new `config -> client` edge |
| `onfhir-validation` | `FhirContentValidator`, `TypeRestriction` | Stable library ownership |
| `onfhir-r4` | `R4Parser` | Constructor/default injection likely changes |
| `onfhir-core` - must be removed from Spark | `BaseFhirServerConfigurator`, `IFhirAuditCreator`, `AuditConfig` | Spark is in the open-source library release chain and should not gain a GPL/server dependency |

### 6.2 Files most exposed to planned breaking changes

| File | Risk-bearing imports | Recommended migration |
|---|---|---|
| `SparkFhirConfigurator.scala` | `BaseFhirServerConfigurator`, `IFhirAuditCreator`, `AuditConfig`, `R4Parser` | Refactor to `BaseFhirConfigurator` or a new narrow library-grade configuration builder; remove audit and DB/server lifecycle hooks |
| `SparkSchemaUtil.scala` | `FHIRResultParameterResolver`, `FhirQueryParser`, validation/config models | Consume both parsers from `onfhir-query`; supply explicit result defaults |
| `SparkFhirQueryEvaluator.scala` | `FHIRSearchParameterValueParser`, `FhirQueryParser` | Keep search parser in common; consume simple query parser from query module |
| `SparkOnFhir.scala` | `FHIRResultParameterResolver`, `FhirClientUtil` | Add direct query/client dependencies and adapt client construction |
| reader and writer classes | request builders, bundles, `OnFhirNetworkClient`, authentication settings | Compile against relocated builder API and ActorSystem-free client |
| date partition/query handlers | `DateTimeUtil` | Pin date/time boundary behavior before Phase 2 |

### 6.3 Decision

spark-on-fhir changes the split plan in two places:

1. `FhirQueryParser` is not dead externally and must not be deleted.
2. `FHIRResultParameterResolver` is reusable query/result logic and must not be
   moved into the GPL server family.

Both should move from `onfhir-common` to `onfhir-query`, with their
`io.onfhir.api.parsers` packages unchanged.

## 7. onfhir-cds Detailed Usage

| Recommended provider | Imported classes and members | Impact |
|---|---|---|
| `onfhir-common` | `Resource`, `FHIRRequest`, `FHIRResponse`, `OutcomeIssue`, `FHIRUtil`, `IOUtil`, `IFhirResourceValidator`, `DateTimeUtil`, exception wildcard | HTTP/status/header/date migration affects CDS error and rejection handlers |
| `onfhir-client` | `IOnFhirClient`, `FHIRSearchSetBundle`, `FhirClientException`, `FhirReadRequestBuilder`, `FhirSearchRequestBuilder`, `OnFhirNetworkClient`, `IHttpRequestInterceptor`, `FixedBearerTokenInterceptor` | Builder relocation plus client/interceptor transport migration |
| `onfhir-path` | `FhirPathEvaluator` | Expected to remain source-compatible |
| `onfhir-core` | `IFhirConfigurationManager` | Deliberate server integration; CDS cannot be treated as library-family-only without a separate SPI extraction |

### Files most exposed

| File | Risk-bearing imports | Recommended migration |
|---|---|---|
| `CdsCoordinator.scala` | builders, client, interceptor, bearer interceptor | Adapt to `ClientHttpRequest` and ActorSystem-free `OnFhirNetworkClient` |
| `CdsErrorHandler.scala`, `CdsRejectionHandler.scala` | `FHIRRequest`, `FHIRResponse`, `OutcomeIssue` | Adapt neutral status/header models and server Akka adapters |
| `CdsConfig.scala`, `OnFhirCds.scala` | `IOnFhirClient`, `IFhirConfigurationManager` | Declare client and core dependencies explicitly after split |
| `CdsResponseBuilder.scala` | `DateTimeUtil` | Verify `Instant` formatting behavior |

`IFhirConfigurationManager` is the important architectural distinction: unlike
spark-on-fhir, onfhir-cds appears to be a Repofyr server extension. Keeping an
explicit server-family dependency is reasonable unless CDS is separately
required to become a standalone Apache library.

## 8. ignifyr/toFHIR Detailed Usage

The file named `ignifyr-imports.txt` contains `tofhir-*`, `io.tofhir.*`, and
`io.onfhir.definitions.*` modules. This report therefore treats it as the
ignifyr/toFHIR codebase inventory.

| Recommended provider | Imported classes and members | Impact |
|---|---|---|
| `onfhir-common` | `Resource`, FHIR constants, `FHIRUtil`, `IOUtil`, `JsonFormatter`, `OnFhirZipInputStream`, `OutcomeIssue`, foundation parser/validation models, service SPIs, configuration interfaces/models, and `InitializationException` | Large but mostly stable library dependency; explicit-settings signatures may change |
| `onfhir-client` | `FhirBatchTransactionRequestBuilder`, `FHIRTransactionBatchBundle`, `FhirClientException`, `OnFhirNetworkClient`, `FhirClientUtil`, identity/terminology clients, security settings | Used in writers, sources, services, settings, and many tests; must follow client release |
| `onfhir-path` | evaluator, expression evaluator, environment, result/value types, function-library factories, annotations, generated parser context, wildcard imports | Broad extension API; package stability essential |
| `onfhir-expression` | `FhirExpression`, `FhirExpressionException` | Stable library ownership |
| `onfhir-template-engine` | `FhirTemplateExpressionHandler` | Stable library ownership |
| `onfhir-validation` | cardinality and type restrictions | Stable library ownership |
| `onfhir-r4` | `R4Parser` | Explicit default injection likely required |
| `onfhir-config` | `FSConfigReader` | Stable library ownership |
| server-r4/server-r5 - review required | `FhirR4Configurator`, `FhirR5Configurator` | Direct server-family dependency in `AbstractSchemaRepository`; replace if this code must consume only Apache libraries |
| spark-on-fhir | `FhirApiReader._`, `FhirApiReader.OPTIONS` | Creates a release-order dependency on the migrated spark-on-fhir version |

### Files most exposed

| File/group | Risk-bearing imports | Recommended migration |
|---|---|---|
| `FhirRepositoryWriter.scala` and repository/source tests | batch builder, transaction bundle, network client | Add direct client dependency and adapt new construction |
| `FhirSinkSettings.scala`, `FhirServerDataSourceReader.scala` | network/security settings and Spark reader | Migrate after spark-on-fhir publishes its compatible release |
| `AbstractSchemaRepository.scala` | `FhirR4Configurator`, `FhirR5Configurator`, `R4Parser`, version/config interfaces | Replace server configurators with a library-grade version configurator/factory if server independence is required |
| mapping/FHIRPath extensions | path wildcard, annotations, parser context, function factories | Run source compatibility tests against `onfhir-path`; avoid package changes |
| integrated terminology/identity services | client implementations and common SPIs | Adapt client construction while preserving SPI packages |

## 9. Recommended Ownership Changes From This Evidence

| Type | Previous v2 plan | Evidence from consumers | Revised recommendation |
|---|---|---|---|
| `FhirQueryParser` | Delete as dead | Imported twice by spark-on-fhir | Move to `onfhir-query`; rename source file to match class; retain package; replace Akka URI parsing in Phase 2 |
| `FHIRResultParameterResolver` | Move to `onfhir-core` | Imported by `SparkSchemaUtil` and `SparkOnFhir` | Move to `onfhir-query`; inject `FhirResultDefaults`; retain package |
| `BaseFhirServerConfigurator` | Move to core | Spark subclasses/imports it | Still move to core; refactor Spark onto library-grade configuration instead of preserving server lifecycle in libraries |
| `IFhirAuditCreator`, `AuditConfig` | Move to core | Spark imports them through its configurator | Still move to core; remove audit obligations from Spark's library configurator |
| R4/R5 server configurators | Remain server-side | ignifyr imports both | Keep server-side; introduce/reuse a library-grade version configurator only if ignifyr must be server-independent |

## 10. Consumer-Aware Verification Matrix

| Split phase | spark-on-fhir verification | onfhir-cds verification | ignifyr/toFHIR verification |
|---|---|---|---|
| 1A client API relocation | compile reader/writer modules and tests with direct `onfhir-client` | compile coordinator/config | compile writer/settings/service modules and tests |
| 1B Kafka construction decoupling | no direct consumer change; run server-r4 regression | no direct consumer change expected | no direct consumer change expected |
| 1C singleton/API decoupling | parser/result/default characterization tests | no direct singleton usage expected | R4 parser/config reader tests |
| 1D semantic relocations | verify query module imports; remove server configurator/audit coupling | verify explicit core dependency | decide and test R4/R5 configurator replacement |
| 2 neutral HTTP model | date, query, status, ETag and URI tests | error/rejection handlers and response builder | batch result/error handling and parser defaults |
| 3 JDK HTTP client | full API connector read/write/stream suites | coordinator auth/interceptor tests | repository writer, sink/source, identity/terminology tests |
| 4 isolated rehearsal | build against library-only staging artifacts | build with explicit library + core artifacts | build after compatible spark-on-fhir artifact is staged |
| 5 staging release | publish compatible spark-on-fhir release | publish/rebuild CDS as server extension | update after spark-on-fhir; remove all SNAPSHOT pins |

## 11. Release Order

The inventories confirm this dependency order:

```text
onfhir-libs 4.0.0
    -> spark-on-fhir compatible release
        -> ignifyr/toFHIR compatible release

onfhir-libs 4.0.0 + Repofyr/core compatible release
    -> onfhir-cds compatible release
```

ignifyr also directly consumes onFHIR libraries, so it must use the same
library version selected by spark-on-fhir to avoid mixed binary contracts.

## 12. Follow-Up Data Needed

Before Phase 1 implementation, obtain non-truncated source-level inventories
from each consumer containing:

1. exact file paths and source/test scope;
2. resolved Maven dependencies and whether they are direct or transitive;
3. all `akka.*` imports in files that construct or intercept onFHIR clients;
4. method/constructor usages for every API in the high-impact table;
5. current build commands and released onFHIR version pins.

Imports are sufficient to correct ownership decisions, but consumer smoke
builds remain the authority for migration completeness.
