---
name: release-stage
description: Stage a signed local release of the Repofyr server reactor and verify it with check-staged-release.ps1. Follows RELEASING.md sections 1-3 and stops hard before anything publishes or pushes.
---

# Stage and verify a release candidate

Implements RELEASING.md sections 1-3. HARD STOP at the end of section 3:
never run a remote `mvn deploy`, never promote a deployment in the Maven
Central portal, never `git push`, never `docker push`. Publishing is
maintainer-only action outside this skill, and a published release is
immutable.

## 1. Pre-flight

- Run the `verify` skill; `mvn -B test` and the boundary gate must both
  pass. Report both verdicts verbatim.
- Confirm the reactor version (`revision` in the root `pom.xml`) is the
  version being staged, and that it is not a `-SNAPSHOT` if this is a real
  candidate.
- Confirm `onfhir.libs.version` names a released, non-SNAPSHOT `io.onfhir`
  version that is resolvable from Maven Central. If the libraries are not
  yet published, say so and stop: the server cannot be released ahead of
  the library release it depends on.
- Confirm `CHANGELOG.md` has a complete entry for this version, and that
  any server API, package, coordinate, or configuration change since the
  last release is covered by the migration guide under `docs/migration/`.
- `git log`: confirm the tree is the state intended for release. Parallel
  sessions may have moved it.

## 2. Stage signed artifacts

```
mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
```

- The target is a LOCAL file-based repository (for example under
  `C:\tmp`); this is not a publish. A `deploy` without
  `-DaltDeploymentRepository` would upload to the Central portal - never
  run that form.
- Signing requires the SRDC release GPG key on this machine; headless
  signing works via the loopback pinentry already configured in the
  `release` profile. If GPG prompts or fails, stop and report - do not
  disable signing to get a green run. An unsigned rehearsal is only valid
  when the maintainer asked for one, via `-Dgpg.skip=true` plus
  `check-staged-release.ps1 -SkipSignatures`, and its output is not a
  release candidate.

## 3. Verify the staging repository

```
powershell -File scripts/check-staged-release.ps1 -RepositoryPath <staging-path> -Version <version>
```

Expect: `check-staged-release: PASS - 9 <version> artifacts verified.`

`-RepositoryPath` is mandatory; `-Version` defaults to `4.0.0`. The script
checks eight coordinates - `repofyr-parent` (pom) plus the seven
`repofyr-*_2.13` jars - for POM presence, GNU General Public License
metadata with no Apache License, no unresolved `${revision}`, the binary
plus `-sources` and `-javadoc` JARs, packaged `META-INF/LICENSE`, and a
good `.asc` signature on every file.

Run it bare, never piped: under PowerShell 5.1 with
`$ErrorActionPreference = "Stop"` a native stderr line from `gpg` or `jar`
becomes a terminating NativeCommandError.

## 4. Consumer rehearsal (majors and mechanics changes; report, then stop)

For a MAJOR release or any change to publishing mechanics, RELEASING.md
section 3 requires proving the staged artifacts against the release chain:
spark-on-fhir built against staging with the Migration Table applied, then
CRT launch verification, then other internal consumers. These builds live
in sibling repositories. If asked to run them, purge `io/repofyr` (and
`io/onfhir` when the libraries also come from staging) from the
rehearsal's local Maven repository first, so nothing resolves from a stale
cache. For a routine minor or patch release the rehearsal is optional -
note that in the hand-over report instead of running it by default.

## Hand-over report

Finish with a report containing:

- the absolute staging path;
- the artifact count verified and the version;
- every gate verdict, quoted verbatim (`mvn -B test` totals,
  `check-server-boundary`, `check-staged-release`);
- whether the consumer rehearsal ran, and what it found;
- what remains for the maintainer: RELEASING.md section 4 - portal upload,
  portal promotion, tag and push, Docker image build and push, GitHub
  release.
