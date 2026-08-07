# repofyr-server-r4

`repofyr-server-r4` is the runnable Repofyr server for HL7 FHIR R4. It supplies
the small amount of code that is genuinely R4-specific - the configurator, the
AuditEvent creator, and the Subscription strategy - and packages them with the
R4 standard definitions and the default operation implementations into an
executable jar.

Maven coordinate: `io.repofyr:repofyr-server-r4_2.13`. Main class:
`io.repofyr.r4.Boot`. The shaded standalone jar is
`target/repofyr-server-standalone.jar`.

## What is in it

| Type | Purpose |
| --- | --- |
| `io.repofyr.r4.Boot` | Entry point; starts an embedded MongoDB when configured, then `Onfhir.apply(new FhirR4Configurator()).start` |
| `io.repofyr.r4.config.FhirR4Configurator` | `BaseFhirServerConfigurator` for R4; supplies `R4Parser` as the foundation resource parser |
| `io.repofyr.r4.audit.R4AuditCreator` | Builds R4 AuditEvent resources for the audit pipeline |
| `io.repofyr.r4.subscription.R4SubscriptionUtil` | R4 Subscription parsing, criteria validation, and change policy |

`FhirR4Configurator` does not override `fhirVersion`; R4 is the release label
`BaseFhirServerConfigurator` defaults to. The FHIR version reported by the
server comes from the CapabilityStatement, not from that label.

The module also carries `db-index-conf-r4.json`, the default database index and
shard key configuration for R4. Override it with
`fhir.initialization.index-conf-path`.

## What it brings in

| Dependency | Supplies |
| --- | --- |
| `repofyr-core` | the server runtime |
| `repofyr-operations` | the default FHIR operation handlers, resolved by class name at startup |
| `io.onfhir:onfhir-r4` | `R4Parser` for foundation resources |
| `io.onfhir:onfhir-definitions-r4` | `definitions-r4.json.zip` and `conformance-statement-r4.json` on the classpath |
| `io.onfhir:onfhir-validation` | profile and terminology validation |

The definitions are a classpath artifact, not files in this module. A
deployment that wants its own base definitions points
`fhir.initialization.base-definitions-path` at its own zip.

## Running it

```shell
mvn package -pl repofyr-server-r4 -am
java -jar repofyr-server-r4/target/repofyr-server-standalone.jar
```

Override configuration without rebuilding:

```shell
java -Dconfig.file=/etc/repofyr/application.conf \
     -jar repofyr-server-standalone.jar
```

## Extending it

Write your own `Boot` rather than editing this one. `Onfhir.apply` takes the R4
configurator plus whatever you are customizing:

```scala
import io.repofyr.Onfhir
import io.repofyr.r4.config.FhirR4Configurator

object Boot extends App {
  val onfhir = Onfhir.apply(
    fhirConfigurator = new FhirR4Configurator(),
    fhirOperationLibraries = Seq(new MyOperationLibrary()),
    customAuthorizer = Some(new MyAuthorizer()),
    externalRoutes = myRoutes)
  onfhir.start
}
```

Reuse `FhirR4Configurator` as-is unless you need to change how foundation
resources are parsed; the FHIR capabilities you serve are decided by your
CapabilityStatement and profile set, not by subclassing the configurator.

## Tests

This module carries the reactor's main regression net: the suites boot a full
server against an embedded MongoDB instance and exercise the FHIR API end to
end.

| Suite | What it covers |
| --- | --- |
| `FHIRCreateEndpointTest`, `FHIRReadEndpointTest`, `FHIRUpdateEndpointTest`, `FHIRDeleteEndpointTest` | the instance-level interactions, including conditional variants and versioning |
| `FHIRSearchEndpointTest` | every search parameter type and its modifiers and prefixes, `_include`, `_revinclude`, compartment search, and cross-type search |
| `FHIRHistoryEndpointTest` | instance, type, and system level history |
| `FHIRPatchEndpointTest` | JSON Patch and FHIRPath Patch, including conditional patch and invalid-patch rejection |
| `FHIRBatchTransactionEndpointTest` | batch and transaction Bundle processing |
| `OnFhirTest` | not a suite: the shared fixture trait that boots the server and embedded MongoDB for the others |
| `OnFhirLocalClientTest` | the in-process client against the running server |
| `ProfileValidationTest` | profile validation against configured StructureDefinitions |
| `SmartAuthorizerTest` | SMART on FHIR scope evaluation |
| `R4SubscriptionUtilCharacterizationTest` | R4 Subscription parsing and criteria validation |

```shell
mvn -pl repofyr-server-r4 -am test
```

The suites bind the embedded MongoDB port, so do not run them concurrently with
another instance of the same suite.
