# repofyr-server-r5

`repofyr-server-r5` is the runnable Repofyr server for HL7 FHIR R5. It is the
R4 server's structure with the R5 release bindings substituted: the R5
foundation resource parser, the R5 AuditEvent creator, and the R5 standard
definition package.

Maven coordinate: `io.repofyr:repofyr-server-r5_2.13`. Main class:
`io.repofyr.r5.Boot`. The shaded standalone jar is
`target/repofyr-server-standalone.jar`.

## What is in it

| Type | Purpose |
| --- | --- |
| `io.repofyr.r5.Boot` | Entry point; starts an embedded MongoDB when configured, then `Onfhir.apply(new FhirR5Configurator()).start` |
| `io.repofyr.r5.config.FhirR5Configurator` | `BaseFhirServerConfigurator` for R5; supplies `R5Parser` as the foundation resource parser |
| `io.repofyr.r5.audit.R5AuditCreator` | Builds R5 AuditEvent resources; unlike the R4 creator it takes the `AuditConfig` |

The module also carries `db-index-conf-r5.json`, the default database index and
shard key configuration for R5. Override it with
`fhir.initialization.index-conf-path`.

Two R5 differences are expressed as configurator overrides:

- `fhirVersion` is `"R5"`, which is what selects the release-suffixed
  definition, conformance, and index configuration resources on the classpath.
- `VALUESET_AND_CODESYSTEM_BUNDLE_FILES` is narrowed to `valuesets.json`,
  because R5 moved the v2 and v3 terminology content into the separate HL7
  terminology package.

## Subscription is not implemented

`getSubscriptionUtil` returns an `UnsupportedSubscriptionUtil`. Any code path
that would parse or validate an R5 Subscription raises a
`NotImplementedException` carrying a `not-supported` OperationOutcome issue
rather than failing obscurely. Leave `fhir.subscription.active` off for an R5
deployment; R5 replaced the R4 Subscription model with SubscriptionTopic, which
Repofyr does not implement yet.

## What it brings in

| Dependency | Supplies |
| --- | --- |
| `repofyr-core` | the server runtime |
| `repofyr-operations` | the default FHIR operation handlers, resolved by class name at startup |
| `io.onfhir:onfhir-r5` | `R5Parser` for foundation resources |
| `io.onfhir:onfhir-definitions-r5` | `definitions-r5.json.zip` and `conformance-statement-r5.json` on the classpath |
| `io.onfhir:onfhir-validation` | profile and terminology validation |

## Running it

```shell
mvn package -pl repofyr-server-r5 -am
java -jar repofyr-server-r5/target/repofyr-server-standalone.jar
```

## Extending it

Write your own `Boot` and pass `FhirR5Configurator` to `Onfhir.apply`, as for
R4:

```scala
import io.repofyr.Onfhir
import io.repofyr.r5.config.FhirR5Configurator

object Boot extends App {
  Onfhir.apply(new FhirR5Configurator()).start
}
```

## Tests

| Suite | What it covers |
| --- | --- |
| `XFhirQueryParserTest` | x-fhir-query parsing against a real R5 server configuration built from the packaged R5 definitions |

The suite builds a full `FhirServerConfig` through `FhirR5Configurator` and
`FSConfigReader`, so it doubles as the R5 startup smoke test: a broken
definition package or a misnamed release resource fails it.

```shell
mvn -pl repofyr-server-r5 -am test
```
