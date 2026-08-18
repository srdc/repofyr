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
mvn -B -Prelease deploy -DskipPublishing=true -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
```

- **Both flags are mandatory, and neither controls where the artifacts land.**
  `-DskipPublishing=true` is what keeps this local.
  The `release` profile runs `central-publishing-maven-plugin` as an
  extension, and its injected `publish` goal contacts the Central portal
  regardless of `altDeploymentRepository`. Omitting `skipPublishing` created
  a real portal deployment on 2026-08-18 while leaving the staging directory
  empty. `block-remote-publish.sh` now denies a deploy missing either flag.
- **The artifacts are written to `target/central-staging`, not to the path you
  named.** With the plugin running as an extension, `maven-deploy-plugin` never
  runs and `altDeploymentRepository` routes nothing; the plugin uses its own
  `stagingDirectory`. Verify that directory. On 2026-08-18 the named path was
  searched, found absent, and mistaken for a failed staging run. The flag stays
  in the command because the hook requires it, and because it would matter again
  if the extension were ever removed.
- Signing requires the SRDC release GPG key on this machine; headless
  signing works via the loopback pinentry already configured in the
  `release` profile. If GPG prompts or fails, stop and report - do not
  disable signing to get a green run. An unsigned rehearsal is only valid
  when the maintainer asked for one, via `-Dgpg.skip=true` plus
  `check-staged-release.ps1 -SkipSignatures`, and its output is not a
  release candidate.

## 3. Verify the staging repository

```
powershell -File scripts/check-staged-release.ps1 -RepositoryPath target/central-staging -Version <version>
```

Expect: `check-staged-release: PASS - 9 <version> artifacts verified.`

`-RepositoryPath` is `target/central-staging`, not whatever
`altDeploymentRepository` named; `-Version` defaults to `4.0.0`. The script
checks nine coordinates - `repofyr-parent` (pom), the seven
`repofyr-*_2.13` server jars, and `repofyr-embedded-mongo_2.13` -
for POM presence, GNU General Public License metadata with no Apache
License, no unresolved `${revision}`, the binary plus `-sources` and
`-javadoc` JARs, packaged `META-INF/LICENSE`, and a good `.asc`
signature on every file.

Run it bare, never piped: under PowerShell 5.1 with
`$ErrorActionPreference = "Stop"` a native stderr line from `gpg` or `jar`
becomes a terminating NativeCommandError.

## 4. Consumer rehearsal - not required

RELEASING.md section 3 records the decision of 2026-08-18: a Repofyr server
release does not gate on a consumer rehearsal, not even a major. 4.0.0 shipped
without one. Consumers pin their versions, and the section 1 gates plus the
staging checks above are the acceptance bar. The rehearsal for the reusable
libraries belongs to `srdc/onfhir-libs`.

Say in the hand-over report that it was not run and why, rather than leaving it
as an outstanding item.

## Hand-over report

Finish with a report containing:

- the absolute staging path;
- the artifact count verified and the version;
- every gate verdict, quoted verbatim (`mvn -B test` totals,
  `check-server-boundary`, `check-staged-release`);
- that the consumer rehearsal was not run, and why (section 4);
- what remains for the maintainer: RELEASING.md section 4 - portal upload,
  portal promotion, tag and push, Docker image build and push, GitHub
  release.
