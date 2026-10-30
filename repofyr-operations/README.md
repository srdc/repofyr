# repofyr-operations

`repofyr-operations` contains the FHIR operation implementations Repofyr ships
out of the box. Each one is a concrete `FHIROperationHandlerService` that
`repofyr-core` instantiates when the server's CapabilityStatement declares the
corresponding operation.

Maven coordinate: `io.repofyr:repofyr-operations_2.13`. Its only dependency is
`repofyr-core`. `repofyr-server-r4` and `repofyr-server-r5` depend on it
directly, and `repofyr-server-stu3` receives it transitively through
`repofyr-server-r4`.

## The handlers

All handlers live in `io.repofyr.operation` and take a single
`IFhirConfigurationManager` constructor argument.

| Handler | Operations | Behavior |
| --- | --- | --- |
| `ValidationOperationHandler` | `$validate` | Validates the `resource` parameter against a `profile` in one of the `general`, `create`, `update`, or `delete` modes, and returns the result as an OperationOutcome in the `return` parameter |
| `MetaOperationHandler` | `$meta`, `$meta-add`, `$meta-delete` | Reads, adds to, or removes from `Resource.meta` |
| `ExpandOperationHandler` | `$expand` | Resolves a ValueSet by instance id, `_id`, or `url`, then expands its `compose` into an `expansion` |
| `DocumentOperationHandler` | `$document` | Builds a document Bundle from a Composition and the resources it references (`_include=*`); the `persist` parameter decides whether the Bundle is stored |
| `LastNObservationOperationHandler` | `$lastn` | Returns the last N Observations per code for a subject, as a searchset Bundle |
| `PatientEverythingOperationHandler` | `$everything` | Returns the resources in a Patient compartment, restricted to the compartment relations the server supports and excluding `AuditEvent`, `Group`, and `Provenance` |
| `PatientPurgeOperationHandler` | `$purge` | Permanently removes all current and historical versions of a Patient and of the resources in its Patient compartment, excluding `AuditEvent` and `Group`, and returns an OperationOutcome |
| `BulkOperationHandler` | `$import` | Validates the import request, starts an asynchronous `BulkImportJobHandler` actor, and replies `202 Accepted` |

Two handlers impose constraints beyond the base specification, and both fail
with an explicit OperationOutcome rather than silently degrading:

- `$lastn` requires a `patient` or `subject` search parameter, and requires one
  of the code parameters (`code`, `code-value-concept`, `code-value-date`,
  `code-value-quantity`, `code-value-string`). The code restriction exists
  because the grouping expression cannot be built for multiple coded values.
- `$document` is instance level only; calling it without an id is rejected.

`$import` is not an HL7-defined operation. The module carries its
OperationDefinition as the `bulk-import.json` resource, published under
`http://onfhir.io/fhir/OperationDefinition/import`. It accepts NDJSON input
only, requires an `inputSource` and a `storageDetail` of type `file`, and runs
the job through `io.repofyr.async.BulkImportJobHandler` in `repofyr-core`.
There is no status or cancel operation; the job reports its progress to the
server log at debug level.

`$purge` comes from the FHIR 6.0.0-ballot3 build
(`http://hl7.org/fhir/OperationDefinition/Patient-purge`) and is provided for
R4 and R5. Neither base definition set contains it, so the module carries its
OperationDefinition as the `patient-purge.json` resource. A deployment enables
it by copying that file into its `operations-path` folder and declaring the
operation on the Patient resource of its CapabilityStatement, which must also
declare the Patient compartment under `rest.compartment` (the default R5
CapabilityStatement does not; without it the call answers `405`). The
compartment's search parameters must be declared for every resource type
purged; the call checks this before deleting anything and answers `501` naming
the missing ones. It is a hard
delete meant for erasure requests: unlike a FHIR delete nothing is kept in
history, no resource event is published, and it cannot be undone. The Patient
is matched by id only, so other Patient records reached through
`Patient.link` stay, and the purge is synchronous. If a run fails partway,
the Patient is removed last, so the same call can simply be repeated.

## How a handler reaches the server

The dispatch table lives in `repofyr-core`, not here.
`io.repofyr.operation.DefaultOperationHandlers.DEFAULT_IMPLEMENTED_FHIR_OPERATIONS`
maps each operation URL to the fully qualified class name of its handler, and
`FhirOperationHandlerFactory` resolves that name through the class loader at
startup. `repofyr-core` therefore has no compile-time dependency on this
module; putting the artifact on the runtime classpath is what makes the
operations available.

A handler is only constructed if the server's CapabilityStatement declares the
operation, so an unused handler on the classpath costs nothing. Conversely, a
CapabilityStatement that declares an operation with no implementation fails
startup with an `InitializationException`.

## Adding your own operation

Extend `FHIROperationHandlerService` and implement `executeOperation`:

```scala
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse}
import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.config.IFhirConfigurationManager
import akka.http.scaladsl.model.StatusCodes
import scala.concurrent.Future

class MyOperationHandler(fhirConfigurationManager: IFhirConfigurationManager)
  extends FHIROperationHandlerService(fhirConfigurationManager) {

  override def executeOperation(
    operationName: String,
    operationRequest: FHIROperationRequest,
    resourceType: Option[String],
    resourceId: Option[String]): Future[FHIROperationResponse] = {

    val response = new FHIROperationResponse(StatusCodes.OK)
    response.setComplexOrResourceParam("return", myResult)
    Future.successful(response)
  }
}
```

The constructor signature matters: `FhirOperationHandlerFactory` looks up a
constructor taking exactly one `IFhirConfigurationManager`.

Then supply an OperationDefinition and register the implementation. Wrap it in
an `IFhirOperationLibrary` and pass the library to `Onfhir.apply`:

```scala
class MyOperationLibrary extends IFhirOperationLibrary {
  def listSupportedOperations(): Set[String] =
    Set("http://example.org/fhir/OperationDefinition/my-op")

  def getOperationHandler(url: String)(
    implicit fhirConfigurationManager: IFhirConfigurationManager) =
    new MyOperationHandler(fhirConfigurationManager)
}

Onfhir.apply(new FhirR4Configurator(), Seq(new MyOperationLibrary()))
```

A library may also be a `FhirOperationHandlerFactory` built from your own
URL-to-class-name map, which is the class-path style this module uses.

## Scope boundary

Everything an operation needs - configuration, persistence, validation, search
parameter parsing, and the response types - comes from `repofyr-core` through
the injected `IFhirConfigurationManager`. This module adds no infrastructure of
its own, and nothing else in the reactor depends on it at compile time, so a
handler here can be changed without touching the server runtime.
