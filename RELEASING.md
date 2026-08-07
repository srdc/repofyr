# Releasing Repofyr

Maintainer runbook. Publishing and pushing are never automated: every step
in section 4 requires explicit maintainer action, and agents must stop at
the end of section 3.

The reactor version is the `revision` property in the root `pom.xml`
(flatten-maven-plugin `oss` mode resolves it into published POMs). All
eight published coordinates - the `repofyr-parent` POM plus the seven
`repofyr-*_2.13` server artifacts - release together at that one version.

The server version line is independent of `onfhir.libs.version`, the
`io.onfhir` reusable-library line released from `srdc/onfhir-libs`. Both
lines start at 4.0.0 and will diverge. Repofyr is GPL-3.0; the libraries it
consumes are Apache-2.0. Never change an `io.onfhir` coordinate, package,
or license from this repository.

## Versioning policy

- **Patch** (`4.0.x`): fixes only, no server API or configuration change.
- **Minor** (`4.x.0`): additive only - new operations, new extension
  points, new optional configuration keys. An existing deployment upgrades
  without edits.
- **Major** (`5.0.0`, ...): breaking server API or configuration changes,
  shipped with a migration guide entry under `docs/migration/`.

Configuration keys, persistence identifiers, and stored-data conventions
are part of the compatibility contract. Changing one is a major even when
no Scala signature moves, because a running deployment cannot absorb it
silently. Deprecate in a minor, remove in the following major. Majors are
event-driven: ship one when breaking changes have accumulated, not on a
calendar. Published releases are immutable on Maven Central; fixes always
roll forward as a new version, never in place.

There is deliberately no binary-compatibility gate here. Repofyr is
consumed as a deployable server and as an extension point, not as a broad
library API surface, so the migration guide is the contract that
`srdc/onfhir-libs` enforces with a MiMa baseline.

## 1. Pre-flight

- `git log`/`git status`: confirm the tree is the state you intend to
  release (this repository is sometimes worked on by parallel sessions).
- `CHANGELOG.md`: the entry for this version is complete; stamp the
  release date.
- If any server API, package, coordinate, or configuration change shipped
  since the last release, confirm the migration guide under
  `docs/migration/` covers it.
- **Library version check.** `onfhir.libs.version` in the root `pom.xml`
  must name a released, non-SNAPSHOT `io.onfhir` version that is
  resolvable from Maven Central. A server release whose libraries resolve
  only from a local install or a staging directory is not reproducible for
  anyone else, and Central will reject a POM whose dependencies it cannot
  see. The property is `4.0.0` today and those libraries are NOT yet
  published, so the first server release cannot go out ahead of the
  library release it depends on.
- Full verification suite is green (the `verify` skill):
  1. `mvn -B test` - full reactor, zero failures (251 tests today);
  2. `powershell -File scripts/check-server-boundary.ps1` - expect
     `check-server-boundary: PASS - server modules stay in io.repofyr.*`.
- Fresh-checkout rehearsal: clone into a temporary directory and run the
  reactor tests there. This catches working-copy-only state - an untracked
  configuration file, a stale `target/`, or a locally installed artifact
  that masks a missing dependency declaration.

## 2. Stage a signed release locally

1. The SRDC release GPG key must be importable on the build machine.
   Headless signing works through loopback pinentry, which the `release`
   profile already passes to `maven-gpg-plugin`, so signing does not
   prompt.
2. Deploy the full reactor to a file-based staging repository:

   ```shell
   mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
   ```

   The target is a LOCAL directory (for example under `C:\tmp`); this is
   not a publish. The `release` profile also attaches the sources and
   javadoc JARs that Maven Central requires.

3. Verify the staging repository:

   ```shell
   powershell -File scripts/check-staged-release.ps1 -RepositoryPath <staging-path> -Version <version>
   ```

   `-RepositoryPath` is mandatory; `-Version` defaults to `4.0.0`. The
   script walks the eight coordinates and asserts, for each: the POM
   exists, declares the GNU General Public License, does NOT declare an
   Apache License, and carries no unresolved `${revision}` (which would
   mean flatten did not run); the binary, `-sources` and `-javadoc` JARs
   are present; the binary JAR packages `META-INF/LICENSE`; and every file
   has a good detached `.asc` signature.

   Expect: `check-staged-release: PASS - 8 <version> artifacts verified.`

The license assertion runs in both directions on purpose. Repofyr is
GPL-3.0 and the `io.onfhir` libraries are Apache-2.0, so an accidental
flip either way is a release-blocking defect that no test would catch.

Signing must not be disabled to get a green run. An unsigned rehearsal is
useful only when the maintainer explicitly asks for one, via
`-Dgpg.skip=true` on the deploy plus `-SkipSignatures` on the check; a
repository verified that way is not a release candidate.

The script's artifact table is hand-maintained. Adding a module to the
reactor means adding a row, or the new artifact ships unverified.

## 3. Consumer rehearsal

Required before a MAJOR release, and for any release that changes
publishing mechanics (a new module, coordinate changes, POM or release
profile restructuring). Optional otherwise: consumers pin their versions,
so a routine minor or patch release cannot break them retroactively, and
the gates in section 1 plus the staging checks in section 2 are the
acceptance bar.

Purge `io/repofyr` from the rehearsal's local Maven repository first - and
`io/onfhir` too when the libraries are being released from staging in the
same cycle - so the consumer builds can only resolve from the staged
repository. A stale cached artifact otherwise produces a false pass.

1. **spark-on-fhir**: build it against the staged artifacts, applying the
   Migration Table in the migration guide. Server-owned types moved from
   `io.onfhir.*` to `io.repofyr.*` and the coordinates changed with them,
   so this is a source migration and not a version bump. Run its complete
   build, not a subset.
2. **CRT**: point it at the released spark-on-fhir and `io.onfhir` library
   versions, remove any SNAPSHOT or local-Maven workaround, and run its
   launch verification.
3. **Other internal and public consumers**: they migrate on their own
   release schedules and do not gate this release once 1 and 2 pass.
4. Record every omission the rehearsal discovers back into the migration
   guide and the release notes. The rehearsal exists to find gaps in that
   guide, so a discovery is a successful rehearsal, not a failed one.

**Agents stop here.** Everything below is maintainer-only and explicitly
authorized per release.

## 4. Publish (maintainer only, explicitly authorized)

1. Upload to the Central portal:

   ```shell
   mvn -B -Prelease deploy
   ```

   The `release` profile configures `central-publishing-maven-plugin` with
   `autoPublish=false` and `waitUntil=validated`: this uploads the bundle,
   waits for Central to validate it, and stops. Nothing is public yet.
   Credentials come from the `central` server entry in the maintainer's
   `settings.xml`.
2. Promote the validated deployment in the Central portal under the SRDC
   account. This is the irreversible step - a published release is
   immutable and a mistake can only be rolled forward - so review the
   deployment's artifact list once more before publishing it.
3. Tag `v<version>` and push the repository and the tag.
4. Build and push the Docker images. From the repository root, after the
   reactor has been built (`Dockerfile-addJar` copies the already-built
   `repofyr-server-standalone.jar`; `docker/build.sh` records the same
   commands):

   ```shell
   docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
   docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r5 -t srdc/repofyr:r5 .
   ```

   Tag each image with the release version as well as the floating
   `r4`/`r5` tag, then push both tags, so a deployment can pin an exact
   build. `Dockerfile-buildJar` is the alternative that builds inside the
   image; it skips tests via `-Pxtest`, which makes it unsuitable as the
   release build.
5. Create the GitHub release: changelog excerpt plus a link to the
   migration guide.

## 5. Post-publish

- Convert each entry in `docs/release/known-limitations.md` into a GitHub
  issue and link the issue numbers back into that file.
- Bump `revision` in the root `pom.xml` to the next development version.
- Verify the README Maven Central badge renders. It reports "not found"
  until the first publish, so this check only becomes meaningful now.
- Confirm the published POMs resolve: fetch one server artifact into a
  clean local repository and check that its `io.onfhir` dependencies
  resolve from Central without any local install.
- Announce as appropriate (release notes, downstream consumers).
