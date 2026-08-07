# Repofyr (formerly onFHIR)

[![Maven Central](https://img.shields.io/maven-central/v/io.repofyr/repofyr-core_2.13.svg)](https://search.maven.org/search?q=g:io.repofyr)
[![Research by SRDC](https://img.shields.io/badge/Research-SRDC-red)](https://srdc.com.tr)
[![Commercial Support](https://img.shields.io/badge/Commercial%20Support-Pontegra-blue)](https://pontegra.com)

> [!IMPORTANT]
> **Rebranding Announcement**
> 
> **onFHIR** has been officially rebranded as **Repofyr**. This change reflects our transition from a research-focused repository at [**SRDC**](https://srdc.com.tr) to a commercially supported product line by [**Pontegra**](https://pontegra.com).
>
> **Note on Technical Migration:** As of version 4.0.0 the server's Maven coordinates (`io.repofyr:repofyr-*`) and Scala packages (`io.repofyr.*`) carry the new identity. Runtime configuration keys in `application.conf`, Kafka topic names, and stored-data conventions are unchanged, and the values that carry the legacy `onfhir` naming (the `onfhir.subscription` Kafka topic, the `onfhir` database name and Kafka client id) intentionally keep it for operational compatibility. The reusable libraries consumed from [`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs) keep their `io.onfhir` coordinates and packages.
>
> **Upgrading from onFHIR 3.x?** Follow the [migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md). Your configuration, database, and Kafka traffic carry over untouched; what changes is coordinates, package names, and the standalone jar name.

## Overview
**Repofyr** is a FHIR-compliant, secure health data repository designed as a central data service for healthcare applications. It is implemented in **Scala**, built on the **Akka** framework, and utilizes **MongoDB** for high-performance persistence.

You can use Repofyr as a standalone server or extend it with custom FHIR Operations to build complex application layers. It uses FHIR Infrastructure Resource definitions (CapabilityStatement, StructureDefinition, SearchParameter, etc.) to tailor the server to your specific profile and search requirements.

## Modules

This repository builds the Repofyr server family. Most deployments depend on a
single `repofyr-server-*` module and receive the rest transitively.

| Module | Purpose |
|---|---|
| [`repofyr-core`](repofyr-core/README.md) | Core server runtime: FHIR API endpoints, request and response handling, MongoDB persistence, authorization, and audit. |
| [`repofyr-operations`](repofyr-operations/README.md) | The FHIR operations provided out of the box: `$validate`, `$everything`, `$document`, `$expand`, `$meta`, `$lastn`, and bulk `$import`. |
| [`repofyr-event`](repofyr-event/README.md) | Server event contracts and JSON marshalling; the event bus SPI resource changes are published through. |
| [`repofyr-kafka`](repofyr-kafka/README.md) | Publishes server events and FHIR Subscription notifications to Kafka topics. |
| [`repofyr-server-r4`](repofyr-server-r4/README.md) | Runnable server for FHIR R4. |
| [`repofyr-server-r5`](repofyr-server-r5/README.md) | Runnable server for FHIR R5. |
| [`repofyr-server-stu3`](repofyr-server-stu3/README.md) | Runnable server for FHIR STU3. |
| [`repofyr-embedded-mongo`](repofyr-embedded-mongo/README.md) | Starts an embedded MongoDB for development and tests. Not carried by any runnable server. |
| [`repofyr-dev-server`](repofyr-dev-server/README.md) | Development launcher: embedded MongoDB plus the server for a chosen FHIR release. Not published. |

## Reusable onFHIR libraries

The reusable modules now live in the independent
[`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs) repository and retain
their `io.onfhir` coordinates and package names. Repofyr consumes their
independent version through the `onfhir.libs.version` Maven property. This
repository contains only the server family and remains GPL-3.0.

* **Website:** [repofyr.io](https://repofyr.io)
* **Open Source Core:** Maintained by [SRDC](https://srdc.com.tr)

## Basic Configuration
Repofyr ships its defaults as **repofyr-core/src/main/resources/repofyr-reference.conf**, a fully commented file listing every key and its default. Read it as the reference; you do not need to copy it.

To tailor the server, write your own `application.conf` containing **only the keys you change** and point the server at it with `-Dconfig.file`. It layers over the shipped defaults, so anything you leave out keeps its default and picks up improvements when you upgrade:

```hocon
mongodb {
  host = "mongo.internal:27017"
  db = clinical
}
```

For logger configurations, check **repofyr-core/src/main/resources/logback.xml**

To configure the FHIR API to be provided, you need to supply the following:
* A file providing your **Conformance statement** (FHIR Capability Statement - See http://hl7.org/fhir/capabilitystatement.html) that describes the capabilities of the FHIR server you want to provide
* A folder including all your **Profile definitions** (FHIR StructureDefinition - See http://hl7.org/fhir/structuredefinition.html) including resource, data type and extension definitions that will be used in the FHIR server you want to provide
* A folder including all your **Compartment definitions** (FHIR CompartmentDefinition - See http://hl7.org/fhir/compartmentdefinition.html) for all compartments that you want to support for search
* A folder including all your **Search parameter definitions** (FHIR SearchParameter - See http://hl7.org/fhir/searchparameter.html) for all extra search parameters (apart from what is available from the base FHIR standard) that you define and support for your resources
* A folder including all your **Value sets** (FHIR ValueSet - See http://hl7.org/fhir/valueset.html) that you define and refer in your resource profiles
* A folder including all your **Operation definitions** (FHIR OperationDefinition - http://hl7.org/fhir/operationdefinition.html) that you define and refer from capability statement in operations part (For your OperationDefinitions write the full class path of your implementation of operation in OperationDefinition.name)

You can also provide the ZIP file for FHIR base definitions (validation package: `validation-min.xml.zip`) that you want to support specifically.
Repofyr supports all stable and build versions of HL7 FHIR. In this project, we provide modules for the last three main versions, configured automatically with standard definitions and dedicated configurators:
* R5    >> repofyr-server-r5
* R4    >> repofyr-server-r4
* STU3  >> repofyr-server-stu3

## Prerequisites
Repofyr requires a MongoDB database up and running. If you do not use the provided Docker containers, the MongoDB configuration parameters (host, port, dbname, etc.)
should be passed to Repofyr through either the `application.conf` file or runtime parameters. Parameter names can be seen in the provided `application.conf` file.

For development you do not need to install one. [`repofyr-dev-server`](repofyr-dev-server/README.md) starts an embedded
MongoDB and boots the server for a chosen FHIR release against it, defaulting to R5:

```
$ mvn -pl repofyr-dev-server -am exec:java -Dexec.args=r4
```

The runnable `repofyr-server-*` artifacts deliberately do not carry the embedded database, so `mongodb.embedded = true`
is rejected at startup with a message pointing here.

## Build & Run

Run the command below to build Repofyr. This will compile
your code, execute unit tests and create a single standalone jar with all the dependencies:
```
$ mvn package
```

Unit tests may take some time, so you can add **-DskipTests=true** command line parameter 
to the above command to skip the test execution, but it is **not recommended**:
```
$ mvn package -DskipTests=true
```

Executable standalone JARs (**target/repofyr-server-standalone.jar**) will be created under each `repofyr-server-*` module for
different FHIR versions. Executing the following command will run the Repofyr server for that version with nearly complete FHIR
capabilities.
```
$ java -jar target/repofyr-server-standalone.jar
```

You can override in-app configurations by supplying an external application.conf file or JAVA arguments
using the following commands. Both layer over the shipped defaults, so the file needs to carry only
the keys you change, and a `-D` argument outranks the file:
```
$ java -Dconfig.file={path-to-application.conf} -jar target/repofyr-server-standalone.jar
$ java -Dserver.port=9999 -Dserver.host=172.17.0.1 -jar target/repofyr-server-standalone.jar
```

### Extensibility
You can develop your own FHIR-compliant backend application based on Repofyr. To do this, you can import the
corresponding server module as a dependency in your project and write a Scala App (Boot) that initializes Repofyr with a
custom configuration:

```xml
<dependency>
    <groupId>io.repofyr</groupId>
    <artifactId>repofyr-server-r4_2.13</artifactId>
    <version>4.0.0</version>
</dependency>
```

**Onfhir.scala** is the main entry-point of the project. The following is the default server Boot
configuration for repofyr-server-r4. It initiates a FHIR R4 server with the given configurations. 
```
object Boot extends App {
  // Initialize Onfhir for R4
  var onfhir = Onfhir.apply(new FhirR4Configurator())
  // Start it
  onfhir.start
}
```
You can extend Repofyr by implementing certain custom mechanisms:
* Custom Authorizer (implementing **io.repofyr.authz.IAuthorizer**): By default (if configured), Repofyr
supports the authorization mechanism defined by the [SMART on FHIR](https://docs.smarthealthit.org/authorization/) initiative,
which is based on OAuth 2.0 Bearer Token authorization. If you need a custom authorization mechanism with a different set of
scopes (permissions), you can implement an authorizer module and register it with Repofyr.
* Custom Token Resolver (implementing **io.repofyr.authz.ITokenResolver**): Repofyr supports two default token
resolution methods: signed JWT tokens and OAuth 2.0 token introspection. You can use them via configuration or implement a new module.
* Custom Audit Handler (implementing **io.repofyr.authz.ICustomAuditHandler**): By default, you can configure Repofyr
to store FHIR AuditEvent records in its own local repository, or in a remote FHIR server running as a separate audit repository.
If you want to create audit events/logs in a different format and send them to a custom audit repository (Elasticsearch + Kibana, etc.),
you can extend this interface with your module and register it.
* Further FHIR Operations: You can implement custom FHIR operations by extending **io.repofyr.api.service.FHIROperationHandlerService** and
preparing an OperationDefinition file describing the input and output parameters of the operation. Group your handlers behind an
implementation of **io.repofyr.operation.IFhirOperationLibrary**, which declares the operation URLs it supports via `listSupportedOperations()`
and returns the handler for a given URL via `getOperationHandler(url)`. Pass your libraries to `Onfhir.apply` as `fhirOperationLibraries`.
* External Akka Routes: You can also implement non-FHIR REST services for your server and register them with Repofyr.

```
object Boot extends App {
  // Initialize Onfhir for R4
  var onfhir =
     Onfhir.apply(
        fhirConfigurator = new FhirR4Configurator(),
        fhirOperationLibraries = Seq(new MyOperationLibrary()),
        customAuthorizer = Some(new MyAuthorizer()),
        customAuditHandler = Some(new MyAuditHandler()),
        externalRoutes = myNonFhirRoutes
     )
  // Start it
  onfhir.start
}
```
      
### Docker
We also provide a simple Docker setup for Repofyr under the `docker` folder. It includes a `docker-compose` file with
two containers: one for MongoDB and one for the Repofyr application, plus a sample Repofyr setup in the `sample-setup`
directory. The sample setup's CapabilityStatement declares FHIR R4, so it runs against the `srdc/repofyr:r4` image.

First build the standalone jar, then build the image from the repository root - the Dockerfile copies the jar out of
the server module's `target` directory:

```
$ mvn package -pl repofyr-server-r4 -am
$ docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
```

Then start the sample setup. `docker-compose.yml` mounts `sample-setup/conf` into the container as the server's
configuration directory:

```
$ cd docker
$ docker compose -f docker-compose.yml -p onfhir up -d
```

To run an R5 server instead, build with `--build-arg FHIR_VERSION=r5 -t srdc/repofyr:r5`, point the compose `image`
at that tag, and replace `sample-setup/conf` with R5 conformance resources. See `docker/build.sh` for the full set of
build commands.

Then you will be able to send requests to this running instance from your Docker host. The following command returns the CapabilityStatement:
```
$ curl http://127.0.0.1:8080/fhir/metadata
```

## Tests 

Repofyr uses **specs2** for unit testing. To execute tests for each build, run the following command:
```
$ mvn test
```

The `repofyr-server-r4` suite boots a full server against an embedded MongoDB instance, so it is the main regression
net for the reactor.

## Documentation

* [Migration guide: onFHIR 3.x to Repofyr 4.0.0](docs/migration/onfhir-3.x-to-repofyr-4.0.md)
* [Changelog](CHANGELOG.md)
* [Known limitations](docs/release/known-limitations.md)
* [Contributing](CONTRIBUTING.md) and [security policy](SECURITY.md)
* [Release runbook](RELEASING.md) (maintainers)

## License

Repofyr is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

The reusable onFHIR libraries that Repofyr depends on are licensed separately under **Apache License 2.0** and are
released from [`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs). Depending on those libraries does not place
your project under the GPL; embedding Repofyr itself does.
