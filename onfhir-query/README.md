# onfhir-query

Parses FHIR search and x-fhir-query expressions and evaluates supported search
parameters against FHIR resources represented as json4s `JObject` values.

Maven coordinate: `io.onfhir:onfhir-query_2.13`. Principal APIs are
`FhirQueryParser`, `FHIRSearchParameterValueParser`,
`FHIRResultParameterResolver`, `XFhirQueryParser`, `XFhirQueryUtil`, and the
historically named `ImMemorySearchUtil`. Query depends on Common, Expression,
and Path. It does not execute database queries or expose HTTP routes.

```scala
import io.onfhir.api.parsers.FhirQueryParser
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig}

val configured: FhirServerConfig = loadApplicationFhirConfiguration()
val parser = new FhirQueryParser(configured, FhirSearchHandling.Strict)
val (resourceType, parameters) = parser.parseQuery("Patient?gender=male")
```

The supplied `FhirServerConfig` must contain the resource and search-parameter
definitions referenced by a query. Strict/lenient handling affects unsupported
or malformed search parameters. In-memory evaluation implements the supported
FHIR search parameter types and modifiers over one parsed resource; it is not
a replacement for indexed server-side search. `OrderedQuery` in Common
preserves duplicate keys, ordering, encoding, and absent-versus-empty values.

Executable examples are maintained in
[`FhirQueryParserEncodingTest`](src/test/scala/io/onfhir/api/parsers/FhirQueryParserEncodingTest.scala)
and
[`InMemorySearchUtilCharacterizationTest`](src/test/scala/io/onfhir/api/util/InMemorySearchUtilCharacterizationTest.scala).
Run them with `mvn -pl onfhir-query -am test` from the repository root.
