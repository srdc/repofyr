# Library Relicensing Audit

Status: **approved**
Evidence captured: 2026-08-03
Target repository: `srdc/onfhir-libs`
Proposed license after approval: Apache License 2.0

This record covers the nine reusable modules only. The current monorepo and
Repofyr server repository remain GPL-3.0. No license file may be replaced until
the decision owner records approval here.

## Contributor evidence

The following identities were produced by:

```shell
git shortlog -sne --all -- onfhir-common onfhir-client onfhir-path onfhir-query onfhir-config onfhir-expression onfhir-validation onfhir-template-engine onfhir-r4
```

Aliases that appear to describe the same person are grouped. The maintainer
confirmed that all listed contributors are current or former SRDC employees
and that SRDC has authority to relicense their contributions.

| Contributor identity or alias group | Commits reported | Resolution |
|---|---:|---|
| Tuncay NAMLI / Tuncay Namli / Tuncay Namli (Gmail) | 263 | covered by SRDC employment/IP authority |
| A. Anil Sinaci / A. Anil SINACI | 38 | covered by SRDC employment/IP authority |
| Dogukan Cavdaroglu / dogukan10 | 31 | covered by SRDC employment/IP authority |
| emrecam | 8 | covered by SRDC employment/IP authority |
| Mustafa Yuksel (SRDC/Gmail) | 5 | covered by SRDC employment/IP authority |
| yemregurses | 4 | covered by SRDC employment/IP authority |
| Bunyamin Sarigul | 2 | covered by SRDC employment/IP authority |
| Suat Gonul | 2 | covered by SRDC employment/IP authority |
| keremyilmaz | 1 | covered by SRDC employment/IP authority |
| Senan Postaci | 1 | covered by SRDC employment/IP authority |

The raw author spellings and email addresses remain available in Git history;
this document intentionally avoids duplicating personal email addresses.
Bot-only commits do not establish copyright ownership and are excluded from
the human approval list.

## Decision basis

On 2026-08-03, the onFHIR/Repofyr maintainer confirmed that every human
contributor listed above is a current or former SRDC employee and that SRDC has
authority to relicense the contributions. This employment/IP attestation, not
project funding or EU-project participation alone, is the basis for approval.
Confidential employment agreements are intentionally not committed here.

## Approval record

- Decision owner: **onFHIR / Repofyr maintainer on behalf of SRDC**
- Authority/consent evidence: **maintainer employment/IP attestation recorded above**
- External contributor resolution: **none; all listed contributors confirmed as current or former SRDC employees**
- Approved for Apache-2.0 extraction: **yes**
- Approval date: **2026-08-03**

This approval unblocks Phase 5 extraction and relicensing of the nine library
modules. It does not authorize changing the GPL-3.0 license of this monorepo or
the Repofyr server repository.
