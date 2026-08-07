---
name: new-operation
description: Checklist for adding a FHIR operation to the Repofyr server so the handler, its OperationDefinition, the dispatch table, and every FHIR-version module are updated together. Use when creating or exposing a new FHIR operation.
---

# Add a FHIR operation

A new operation touches more than one file, and two of its links are not
checked by the compiler. Work through ALL of these.

1. **Handler** in `repofyr-operations/src/main/scala/io/repofyr/operation/`:
   a class extending
   `io.repofyr.api.service.FHIROperationHandlerService`, taking exactly
   one constructor argument of type `IFhirConfigurationManager`.
   `FhirOperationHandlerFactory` instantiates handlers reflectively through
   that single-argument constructor, so a different signature compiles
   fine and fails at runtime with "Operation service not available". Only
   `executeOperation` needs overriding.
2. **Dispatch table** - if the operation ships enabled by default, add its
   `OperationDefinition.url` and the fully qualified handler class name to
   `DEFAULT_IMPLEMENTED_FHIR_OPERATIONS` in `DefaultOperationHandlers`
   (`repofyr-core/src/main/scala/io/repofyr/operation/`).
   A deployment-specific operation stays out of that map and is supplied
   by the deployment instead.
3. **OperationDefinition JSON** describing the input and output
   parameters. Put the **fully qualified handler class name in
   `OperationDefinition.name`** - that field is how the server resolves
   the implementation class (parsed into `OperationConf.classPath`).
   `repofyr-operations/src/main/resources/bulk-import.json` is the
   in-repository example. Keep `url` stable once published; it is the
   dispatch key and part of the compatibility contract.
4. **CapabilityStatement**: reference the operation from the statement
   that should advertise it, under the appropriate `rest.resource` or
   system-level `rest.operation`. An operation the CapabilityStatement
   does not name is not served, regardless of the handler and definition
   being correct.
5. **Every FHIR-version module that should expose it**: the conformance
   resources are per-version. Adding an operation to `repofyr-server-r4`
   alone leaves it absent from `repofyr-server-r5` and
   `repofyr-server-stu3` with no error anywhere. Decide deliberately which
   versions get it, and say so in the changelog entry when the answer is
   not all three.
6. **Deployment configuration**: a deployment points
   `fhir.initialization.operations-path` at the folder holding its
   OperationDefinitions and `conformance-path` at its CapabilityStatement.
   If the new operation needs either, update the sample setup under
   `docker/sample-setup/conf/` so the shipped example still starts.
7. **Endpoint test**: add a test under
   `repofyr-server-r4/src/test/scala/io/repofyr/api/endpoint/` that
   invokes the operation over HTTP. Unit-testing the handler in isolation
   does not exercise steps 2 to 5, which is where operations actually
   break. The suite boots a full server on embedded MongoDB, so an
   endpoint test proves the whole chain.
8. **Records**: update the module README and the operations section of the
   root `README.md` if the operation is user-visible, and add a
   `CHANGELOG.md` entry. A new operation is an additive change, so it
   belongs in a minor release.
9. **Verify**: run the `verify` skill, plus
   `mvn -B -pl repofyr-server-r4 -am test` while iterating.

## The two links no compiler checks

- **The handler class name is a string.** It appears in
  `DefaultOperationHandlers` and again in `OperationDefinition.name`, and
  both are resolved reflectively at runtime. A typo, a rename, or a moved
  package compiles cleanly and fails only when the operation is first
  invoked. After any package or class rename, grep the string form -
  including in JSON resources and deployment configuration - not just the
  Scala references.
- **Per-version conformance is silent.** An operation registered in one
  FHIR-version module simply does not exist in the others. Nothing warns,
  and the R4 test suite will not notice. Check all three server modules
  before calling the operation done.
