package io.onfhir.expression

import org.specs2.mutable.Specification

class XFhirQueryParserRegexTest extends Specification {

  sequential

  "XFhirQueryParserRegex.parseRawQueryPreserveSpecials" should {

    "parse a simple query without placeholders" in {
      val q = "status=active&subject=Patient/123"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "status"  -> Seq("active"),
        "subject" -> Seq("Patient/123")
      )
    }

    "preserve %claims FHIRPath env variable access inside placeholder" in {
      val q = "performer={{%claims.fhirUser}}"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "performer" -> Seq("{{%claims.fhirUser}}")
      )
    }

    "not split on ampersand inside placeholder" in {
      val q = "performer={{ iif(%x='a&b','Practitioner/1','Practitioner/2') }}&status=active"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "performer" -> Seq("{{ iif(%x='a&b','Practitioner/1','Practitioner/2') }}"),
        "status"    -> Seq("active")
      )
    }

    "not split key/value on equals sign inside placeholder" in {
      val q = "identifier={{ iif(%x='a=b','sys|1','sys|2') }}&status=active"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "identifier" -> Seq("{{ iif(%x='a=b','sys|1','sys|2') }}"),
        "status"     -> Seq("active")
      )
    }

    "support repeated parameters" in {
      val q = "category=laboratory&category={{%ctx.category2}}&status=final"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "category" -> Seq("laboratory", "{{%ctx.category2}}"),
        "status"   -> Seq("final")
      )
    }

    "support parameters with prefix" in {
      val q = "category=laboratory&date=gt{{today()}}&status=final"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "category" -> Seq("laboratory"),
        "date" -> Seq("gt{{today()}}"),
        "status"   -> Seq("final")
      )
    }

    "support parameter with empty value" in {
      val q = "status&subject=Patient/123"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "status"  -> Seq(""),
        "subject" -> Seq("Patient/123")
      )
    }

    "support parameter without equals as empty string value" in {
      val q = "status=active&_summary"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "status"    -> Seq("active"),
        "_summary"  -> Seq("")
      )
    }

    "parse placeholder-only value and plain values together" in {
      val q = "performer={{%claims.fhirUser}}&date=ge2024-01-01&code=http://loinc.org|1234-5"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "performer" -> Seq("{{%claims.fhirUser}}"),
        "date"      -> Seq("ge2024-01-01"),
        "code"      -> Seq("http://loinc.org|1234-5")
      )
    }

    "support prefixed parameter values with ampersand inside placeholder" in {
      val q = "date=gt{{ iif(%x='a&b','2024-01-01','2025-01-01') }}&status=final"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "date"   -> Seq("gt{{ iif(%x='a&b','2024-01-01','2025-01-01') }}"),
        "status" -> Seq("final")
      )
    }

    "support prefixed parameter values with equals sign inside placeholder" in {
      val q = "date=gt{{ iif(%x='a=b','2024-01-01','2025-01-01') }}&status=final"

      XFhirQueryUtil.parseRawQueryPreserveSpecials(q) must_== Map(
        "date"   -> Seq("gt{{ iif(%x='a=b','2024-01-01','2025-01-01') }}"),
        "status" -> Seq("final")
      )
    }
  }
}