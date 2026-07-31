# Repofyr (formerly onFHIR)

[![Maven Central](https://img.shields.io/maven-central/v/io.onfhir/onfhir-core_2.13.svg)](https://search.maven.org/search?q=g:io.onfhir)
[![Research by SRDC](https://img.shields.io/badge/Research-SRDC-red)](https://srdc.com.tr)
[![Commercial Support](https://img.shields.io/badge/Commercial%20Support-Pontegra-blue)](https://pontegra.com)

> [!IMPORTANT]
> **Rebranding Announcement**
> 
> **onFHIR** has been officially rebranded as **Repofyr**. This change reflects our transition from a research-focused repository at [**SRDC**](https://srdc.com.tr) to a commercially supported product line by [**Pontegra**](https://pontegra.com).
>
> **Note on Technical Migration:** While the project identity has changed, the internal codebase - including package names (`io.onfhir`), configuration keys (e.g., `onfhir.conf`), and Maven coordinates - still uses the legacy `onfhir` naming. A full technical migration for these components is planned for future major releases.

## Overview
**Repofyr** is a FHIR-compliant, secure health data repository designed as a central data service for healthcare applications. It is implemented in **Scala**, built on the **Akka** framework, and utilizes **MongoDB** for high-performance persistence.

You can use Repofyr as a standalone server or extend it with custom FHIR Operations to build complex application layers. It uses FHIR Infrastructure Resource definitions (CapabilityStatement, StructureDefinition, SearchParameter, etc.) to tailor the server to your specific profile and search requirements.

* **Website:** [repofyr.io](https://repofyr.io)
* **Open Source Core:** Maintained by [SRDC](https://srdc.com.tr)

## Basic Configuration
You can copy and update the **onfhir-core/src/main/resources/application.conf** file, which is the main entry-point configuration for tailoring the Repofyr repository to your needs.

For logger configurations, check **onfhir-core/src/main/resources/logback.xml**

To configure the FHIR API to be provided, you need to supply the following:
* A file providing your **Conformance statement** (FHIR Capability Statement - See http://hl7.org/fhir/capabilitystatement.html) that describes the capabilities of the FHIR server you want to provide
* A folder including all your **Profile definitions** (FHIR StructureDefinition - See http://hl7.org/fhir/structuredefinition.html) including resource, data type and extension definitions that will be used in the FHIR server you want to provide
* A folder including all your **Compartment definitions** (FHIR CompartmentDefinition - See http://hl7.org/fhir/compartmentdefinition.html) for all compartments that you want to support for search
* A folder including all your **Search parameter definitions** (FHIR SearchParameter - See http://hl7.org/fhir/searchparameter.html) for all extra search parameters (apart from what is available from the base FHIR standard) that you define and support for your resources
* A folder including all your **Value sets** (FHIR ValueSet - See http://hl7.org/fhir/valueset.html) that you define and refer in your resource profiles
* A folder including all your **Operation definitions** (FHIR OperationDefinition - http://hl7.org/fhir/operationdefinition.html) that you define and refer from capability statement in operations part (For your OperationDefinitions write the full class path of your implementation of operation in OperationDefinition.name)

You can also provide the ZIP file for FHIR base definitions (validation package: `validation-min.xml.zip`) that you want to support specifically.
Repofyr supports all stable and build versions of HL7 FHIR. In this project, we provide modules for the last three main versions, configured automatically with standard definitions and dedicated configurators:
* R5    >> onfhir-server-r5
* R4    >> onfhir-server-r4
* STU3  >> onfhir-server-stu3

## Prerequisites
Repofyr requires a MongoDB database up and running. If you do not use the provided Docker containers, the MongoDB configuration parameters (host, port, dbname, etc.)
should be passed to Repofyr through either the `application.conf` file or runtime parameters. Parameter names can be seen in the provided `application.conf` file.

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

Executable standalone JARs (**target/onfhir-server-standalone.jar**) will be created under each `onfhir-server-*` module for
different FHIR versions. Executing the following command will run the Repofyr server for that version with nearly complete FHIR
capabilities.
```
$ java -jar target/onfhir-server-standalone.jar
```

You can override in-app configurations by supplying an external application.conf file or JAVA arguments
using the following commands:
```
$ java -Dconfig.file={path-to-application.conf} -jar target/onfhir-server-standalone.jar
$ java -Dserver.port=9999 -Dserver.host=172.17.0.1 -jar target/onfhir-server-standalone.jar
```

### Extensibility
You can develop your own FHIR-compliant backend application based on Repofyr. To do this, you can import the
corresponding server module as a dependency in your project and write a Scala App (Boot) that initializes Repofyr with a
custom configuration. **Onfhir.scala** is the main entry-point of the project. The following is the default server Boot
configuration for onfhir-server-r4. It initiates a FHIR R4 server with the given configurations. 
```
object Boot extends App {
  // Initialize Onfhir for R4
  var onfhir = Onfhir.apply(new FhirR4Configurator())
  // Start it
  onfhir.start
}
```
You can extend Repofyr by implementing certain custom mechanisms:
* Custom Authorizer (implementing **io.onfhir.authz.IAAuthorizer**): By default (if configured), Repofyr
supports the authorization mechanism defined by the [SMART on FHIR](https://docs.smarthealthit.org/authorization/) initiative,
which is based on OAuth 2.0 Bearer Token authorization. If you need a custom authorization mechanism with a different set of
scopes (permissions), you can implement an authorizer module and register it with Repofyr.
* Custom Token Resolver (implementing **io.onfhir.authz.ITokenResolver**): Repofyr supports two default token
resolution methods: signed JWT tokens and OAuth 2.0 token introspection. You can use them via configuration or implement a new module.
* Custom Audit Handler (implementing **io.onfhir.audit.ICustomAuditHandler**): By default, you can configure Repofyr
to store FHIR AuditEvent records in its own local repository, or in a remote FHIR server running as a separate audit repository.
If you want to create audit events/logs in a different format and send them to a custom audit repository (Elasticsearch + Kibana, etc.),
you can extend this interface with your module and register it.
* Further FHIR Operations: You can implement custom FHIR operations by extending **io.onfhir.api.service.FHIROperationHandlerService** and
preparing an OperationDefinition file describing the input and output parameters of the operation. You then need to provide a Map[String, String]
of the (operation URL -> the class name of the module) that you implemented by extending FHIROperationHandlerService.
* External Akka Routes: You can also implement non-FHIR REST services for your server and register them with Repofyr.

```
object Boot extends App {
  // Initialize Onfhir for R4
  var onfhir = 
     Onfhir.apply(
        fhirConfigurator = new FhirR4Configurator(),
        fhirOperationImplms = myFhirOperations,
        customAuthorizer = new MyAuthorizer(),
        customAuditHandler = new MyAuditHandler(),
        externalRoutes = ...my non-fhir routes 
     )
  // Start it
  onfhir.start
}
```
      
### Docker
We also provide a simple Docker setup for Repofyr under the `docker` folder. It includes a `docker-compose` file with
two containers: one for MongoDB and one for the Repofyr application. You can run it with our sample Repofyr setup in the `sample-setup` directory.
You can copy the `onfhir-server-standalone.jar` file to this `sample-setup` directory and run the sample setup as is with the following command:

```
$ cd docker
$ cp ../onfhir-server-r4/target/onfhir-server-standalone.jar ./sample-setup/.
$ docker-compose -f docker-compose.yml -p onfhir up -d
```

Then you will be able to send requests to this running instance from your Docker host. The following command returns the CapabilityStatement:
```
$ curl http://127.0.0.1:8080/fhir/metadata
```

## Tests 

Repofyr uses **specs2** for unit testing. To execute tests for each build, run the following command:
```
$ mvn test
```
