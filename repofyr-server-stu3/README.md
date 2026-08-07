# repofyr-server-stu3

`repofyr-server-stu3` is the runnable Repofyr server for HL7 FHIR STU3
(3.0.x). It supplies the STU3 configurator and AuditEvent creator and packages
them with the STU3 standard definitions into an executable jar.

Maven coordinate: `io.repofyr:repofyr-server-stu3_2.13`. Main class:
`io.repofyr.stu3.Boot`. The shaded standalone jar is
`target/repofyr-server-standalone.jar`.

## What is in it

| Type | Purpose |
| --- | --- |
| `io.repofyr.stu3.Boot` | Entry point; starts an embedded MongoDB when configured, then `Onfhir.apply(new FhirSTU3Configurator()).start` |
| `io.repofyr.stu3.config.FhirSTU3Configurator` | `BaseFhirServerConfigurator` for STU3; supplies `STU3Parser` as the foundation resource parser |
| `io.repofyr.stu3.audit.STU3AuditCreator` | Builds STU3 AuditEvent resources for the audit pipeline |

The module also carries `db-index-conf-stu3.json`. Unlike R4 and R5, whose
default index configuration paths are named constants, STU3 resolves its file
from the release label - the configurator's `fhirVersion` of `"STU3"` produces
the lookup name `db-index-conf-stu3.json`. The same label selects
`definitions-stu3.json.zip` and `conformance-statement-stu3.json` from
`onfhir-definitions-stu3`. Override the index configuration with
`fhir.initialization.index-conf-path`.

`FhirSTU3Configurator` also overrides
`FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM` to
`http://hl7.org/fhir/v3/ObservationValue`, the pre-terminology.hl7.org URL STU3
uses for the SUBSETTED tag on summarized results. A summarized STU3 read or
search response therefore tags `meta.tag` with that system, while R4 and R5 use
`http://terminology.hl7.org/CodeSystem/v3-ObservationValue`.

Before 4.0.0 this override was inert: `BaseFhirServerConfigurator` copied its
sibling `FHIR_*` fields onto the `FhirServerConfig` that `FHIRServerUtil` reads
but skipped this one, so STU3 emitted the R4-era system. If you match on the
tag `system` rather than its `code`, an STU3 deployment upgrading to 4.0.0 will
see the corrected value. `FhirSTU3ConfiguratorTest` asserts the propagation.

## Subscription is not implemented

`getSubscriptionUtil` returns an `UnsupportedSubscriptionUtil`, so any code
path that would parse or validate an STU3 Subscription raises a
`NotImplementedException` carrying a `not-supported` OperationOutcome issue.
Leave `fhir.subscription.active` off for an STU3 deployment.

## What it brings in

| Dependency | Supplies |
| --- | --- |
| `repofyr-core` | the server runtime |
| `repofyr-server-r4` | on the dependency graph; `repofyr-operations` reaches the classpath through it |
| `io.onfhir:onfhir-stu3` | `STU3Parser` for foundation resources |
| `io.onfhir:onfhir-definitions-stu3` | `definitions-stu3.json.zip` and `conformance-statement-stu3.json` on the classpath |

No STU3 source references any R4 type. The `repofyr-server-r4` dependency is
there for the artifacts it drags in rather than for anything the STU3 code
calls, and it also places the R4 parser and R4 definition package on the
classpath. That is harmless - the release label decides which resources are
read - but it is more than this module needs.

## Running it

```shell
mvn package -pl repofyr-server-stu3 -am
java -jar repofyr-server-stu3/target/repofyr-server-standalone.jar
```

## Extending it

Write your own `Boot` and pass `FhirSTU3Configurator` to `Onfhir.apply`:

```scala
import io.repofyr.Onfhir
import io.repofyr.stu3.config.FhirSTU3Configurator

object Boot extends App {
  Onfhir.apply(new FhirSTU3Configurator()).start
}
```

## Tests

| Suite | What it covers |
| --- | --- |
| `FhirSTU3ConfiguratorTest` | STU3 startup from default paths: the release label, the FHIR version read from the capability statement, the parsed type universes and profiles, the supported resource configurations, and the index configuration |

The suite is deliberately a startup smoke test. It leaves every path at its
default so that it exercises the classpath lookup of all three
release-suffixed resources, which is where STU3 startup has broken before.

```shell
mvn -pl repofyr-server-stu3 -am test
```
