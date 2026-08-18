# Releasing Repofyr

Maintainer runbook. Publishing and pushing are never automated: every step
in section 4 requires explicit maintainer action, and agents must stop at
the end of section 3.

The reactor version is the `revision` property in the root `pom.xml`
(flatten-maven-plugin `oss` mode resolves it into published POMs). All
nine published coordinates - the `repofyr-parent` POM, the seven
`repofyr-*_2.13` server artifacts, and `repofyr-embedded-mongo_2.13` -
release together at that one version. `repofyr-dev-server` is excluded
deliberately; see the `excludeArtifacts` note in the release profile.

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
  see. The property is `4.0.0` today, and `io.onfhir` 4.0.0 is published -
  all fourteen artifacts the reactor depends on resolve from Central - so
  this no longer blocks the first server release. Re-check it whenever
  `onfhir.libs.version` moves: confirm the version resolves from Central
  and not merely from `~/.m2`, which a local `mvn install` in the sibling
  repository will happily satisfy. The fresh-cache build in the next item
  is what actually proves it.
- Full verification suite is green (the `verify` skill):
  1. `mvn -B test` - full reactor, zero failures (334 tests today);
  2. `powershell -File scripts/check-server-boundary.ps1` - expect
     `check-server-boundary: PASS - server modules stay in io.repofyr.*`;
  3. the same reactor against a throwaway Maven cache, which is what
     distinguishes a published dependency from a locally installed one:
     `mvn -B -Dmaven.repo.local=<throwaway> test`. `Dockerfile-buildJar`
     exercises the same property from inside a container, so a successful
     `docker build -f docker/Dockerfile-buildJar` counts as a second
     independent check of it.
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
   mvn -B -Prelease deploy -DskipPublishing=true -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
   ```

   **Both flags are required, and `-DskipPublishing=true` is the one that keeps
   this local.** Through 4.0.0 this step was documented with
   `-DaltDeploymentRepository` alone and described as "not a publish". It was:
   the `release` profile runs `central-publishing-maven-plugin` with
   `<extensions>true</extensions>`, which injects its own `publish` goal into
   the deploy lifecycle, and that goal contacts the Central portal and ignores
   `altDeploymentRepository` entirely. Running the old command on 2026-08-18
   created real portal deployment `31bedb80-2625-4031-babd-2bcc7c198ce5`.
   Nothing became public - `autoPublish=false` and `waitUntil=validated` stop at
   validation - but the staging directory stayed empty, because nothing was ever
   routed to it.

   `skipPublishing` is a parameter of the plugin's `publish` goal: boolean,
   default `false`, exposed as `${skipPublishing}`. Verified against the 0.8.0
   plugin descriptor rather than inferred from documentation.

   `.claude/hooks/block-remote-publish.sh` enforces this, denying any Maven
   deploy that lacks either flag, so the command that caused the accident
   cannot be run from Claude Code again.

   **The staged artifacts do not land at the path you name.** With
   `<extensions>true</extensions>` the publishing plugin takes over the `deploy`
   phase entirely, so `maven-deploy-plugin` never runs and
   `altDeploymentRepository` routes nothing. The artifacts are written to the
   plugin's own `stagingDirectory`, which defaults to `target/central-staging`,
   and the bundle it would upload to `target/central-publishing`. Verify the
   former, not the path you passed - on 2026-08-18 the documented path was
   searched, found absent, and mistaken for a failed run.

   The flag is kept in the command anyway, for two reasons: the hook requires
   both as an explicit signal of intent, and it would start routing again if
   `<extensions>true</extensions>` were ever removed.

   The `release` profile also attaches the sources and javadoc JARs that Maven
   Central requires.

3. Verify the staging repository:

   ```shell
   powershell -File scripts/check-staged-release.ps1 -RepositoryPath target/central-staging -Version <version>
   ```

   `-RepositoryPath` is mandatory and is `target/central-staging` - see above, it is not whatever
   `altDeploymentRepository` named. `-Version` defaults to `4.0.0`. The
   script walks the nine coordinates and asserts, for each: the POM
   exists, declares the GNU General Public License, does NOT declare an
   Apache License, and carries no unresolved `${revision}` (which would
   mean flatten did not run); the binary, `-sources` and `-javadoc` JARs
   are present; the binary JAR packages `META-INF/LICENSE`; and every file
   has a good detached `.asc` signature.

   Expect: `check-staged-release: PASS - 9 <version> artifacts verified.`

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

**Not required for a Repofyr server release.** Decided 2026-08-18, after
4.0.0 shipped without one - itself a major, with renamed coordinates and packages.
Consumers pin their versions and migrate on their own schedule, so a release
cannot break them retroactively; the gates in section 1 and the staging checks
in section 2 are the acceptance bar.

The rehearsal for the reusable libraries belongs to `srdc/onfhir-libs`, whose
artifacts a far wider set of consumers compile against.

If you do want one - a release that restructures the POM or the release profile
is the case that would justify it - the mechanics are below. The trap is worth
reading even if you skip the rest.

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
