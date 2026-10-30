package io.repofyr.operation

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Guards the configuration check that runs before Patient $purge deletes anything.
 *
 * The endpoint suite cannot reach it: its CapabilityStatement declares every base search parameter. A deployment
 * that leaves out one the Patient compartment relies on would otherwise fail halfway through a purge, or keep data
 * reachable only through that parameter. Lives here, not in repofyr-operations, for the same reason as
 * DefaultOperationHandlersTest: that module has no test sources.
 */
@RunWith(classOf[JUnitRunner])
class PatientPurgeOperationHandlerTest extends Specification {

  private val relations = Map(
    "Observation" -> Set("subject", "performer"),
    "Condition" -> Set("subject", "asserter"),
    "Patient" -> Set("_id", "link")
  )

  "Patient $purge configuration check" should {

    "pass when every compartment parameter is supported" in {
      PatientPurgeOperationHandler.findUnsupportedCompartmentParams(relations, Seq("Condition", "Observation"),
        _ => Set("subject", "performer", "asserter", "code")) must beEmpty
    }

    "name each unsupported parameter with its resource type" in {
      PatientPurgeOperationHandler.findUnsupportedCompartmentParams(relations, Seq("Condition", "Observation"), {
        case "Condition" => Set("subject")
        case _ => Set("subject", "performer")
      }) must_== Seq("Condition.asserter")
    }

    "not require _id, which the query builder resolves itself" in {
      PatientPurgeOperationHandler.findUnsupportedCompartmentParams(relations, Seq("Patient"), _ => Set("link")) must beEmpty
    }

    "only check the resource types being purged" in {
      PatientPurgeOperationHandler.findUnsupportedCompartmentParams(relations, Seq("Observation"),
        _ => Set("subject", "performer")) must beEmpty
    }
  }
}
