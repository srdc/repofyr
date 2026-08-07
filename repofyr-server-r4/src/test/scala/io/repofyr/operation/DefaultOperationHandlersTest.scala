package io.repofyr.operation

import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.config.IFhirConfigurationManager
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Guards the default operation dispatch table.
 *
 * `DefaultOperationHandlers` maps an operation URL to a handler **class name held as a string**,
 * which `FhirOperationHandlerFactory` resolves reflectively at request time. Nothing in a build
 * checks those strings: rename or move a handler and the reactor still compiles, the server still
 * starts, and the operation fails only when a client first invokes it.
 *
 * This suite closes that gap without booting a server. It lives in `repofyr-server-r4` because
 * that is the first module where both the table (`repofyr-core`) and the handlers
 * (`repofyr-operations`) are on one classpath - core cannot see the handlers, which is precisely
 * why the coupling is a string in the first place.
 */
@RunWith(classOf[JUnitRunner])
class DefaultOperationHandlersTest extends Specification {

  private val table = DefaultOperationHandlers.DEFAULT_IMPLEMENTED_FHIR_OPERATIONS

  "The default operation dispatch table" should {

    "cover every operation the server advertises as built in" in {
      table.keySet must containAllOf(Seq(
        "http://hl7.org/fhir/OperationDefinition/Resource-validate",
        "http://hl7.org/fhir/OperationDefinition/Resource-meta",
        "http://hl7.org/fhir/OperationDefinition/Resource-meta-add",
        "http://hl7.org/fhir/OperationDefinition/Resource-meta-delete",
        "http://hl7.org/fhir/OperationDefinition/ValueSet-expand",
        "http://hl7.org/fhir/OperationDefinition/Composition-document",
        "http://hl7.org/fhir/OperationDefinition/Observation-lastn",
        "http://hl7.org/fhir/OperationDefinition/Patient-everything",
        "http://onfhir.io/fhir/OperationDefinition/import"))
    }

    // The reflective load is the exact failure this suite exists for. Class.forName here mirrors
    // what FhirOperationHandlerFactory.loadFhirOperationClass does at request time.
    "name a class that actually exists, for every entry" in {
      val unresolvable = table.filter { case (_, className) =>
        scala.util.Try(getClass.getClassLoader.loadClass(className)).isFailure
      }
      unresolvable must beEmpty
    }

    // The factory instantiates via getConstructor(classOf[IFhirConfigurationManager]). A handler
    // that changed its constructor would resolve as a class and then fail at invocation time.
    "expose the constructor the factory instantiates through" in {
      val wrongConstructor = table.filter { case (_, className) =>
        scala.util.Try(
          getClass.getClassLoader
            .loadClass(className)
            .getConstructor(classOf[IFhirConfigurationManager])).isFailure
      }
      wrongConstructor must beEmpty
    }

    "name classes that are FHIROperationHandlerService implementations" in {
      val notHandlers = table.filter { case (_, className) =>
        !classOf[FHIROperationHandlerService].isAssignableFrom(getClass.getClassLoader.loadClass(className))
      }
      notHandlers must beEmpty
    }

    // The rename to io.repofyr is what makes this worth asserting: an OperationDefinition.name
    // still carrying io.onfhir.operation.* is the single most likely thing to break an otherwise
    // clean 3.x upgrade, and the server's own table must not be the one that regresses.
    "resolve every handler under io.repofyr.operation" in {
      table.values.filterNot(_.startsWith("io.repofyr.operation.")) must beEmpty
    }

    // The canonical URLs are a published contract; the $import one is onFHIR specific and
    // deliberately keeps its onfhir.io URL after the rebrand.
    "keep the published import operation URL" in {
      table.get("http://onfhir.io/fhir/OperationDefinition/import") must
        beSome("io.repofyr.operation.BulkOperationHandler")
    }
  }
}
