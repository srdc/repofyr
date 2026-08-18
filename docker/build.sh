#!/usr/bin/env bash
# Reference build commands. Run from the repository root, not from docker/, because both
# Dockerfiles take the repository as their build context.
#
# Forward slashes in -f work on Windows too, so there is one set of commands rather than
# one per platform.
set -euo pipefail

# Read the version from the POM rather than restating it, so an image can never claim a
# version the reactor is not building.
VERSION=$(mvn -B -N -q help:evaluate -Dexpression=project.version -DforceStdout)
REVISION=$(git rev-parse HEAD)
# Stamped here rather than in the Dockerfile: baking a timestamp into the Dockerfile
# would change the digest on every local rebuild, but leaving it unset is worse than
# wrong - the base image supplies its own created date, so an image built today
# inherits eclipse-temurin's and misreports when it was made. Setting it on the
# release build only fixes that without making ad-hoc builds irreproducible.
CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "Building Repofyr images: version=$VERSION revision=${REVISION:0:12}"

# Every image gets two tags: an immutable <version>-<release> tag so a deployment can pin
# an exact build, and the floating <release> tag that moves with each release. Publishing
# only the floating tag leaves no way to pin.
for FHIR in r4 r5 stu3; do
  docker build \
    -f docker/Dockerfile-addJar \
    --build-arg "FHIR_VERSION=$FHIR" \
    --build-arg "VERSION=$VERSION" \
    --build-arg "REVISION=$REVISION" \
    --label "org.opencontainers.image.created=$CREATED" \
    -t "srdc/repofyr:$VERSION-$FHIR" \
    -t "srdc/repofyr:$FHIR" \
    .
done

# Dockerfile-addJar packages an already-built shaded jar, so build the reactor first:
#   mvn clean package
# Use the full build, not -Pxtest: the release images must come from a tested reactor.
#
# Dockerfile-buildJar runs Maven inside the image instead, needing no local build. Because
# the builder starts with an empty Maven cache and can reach only Maven Central, it doubles
# as the reproducibility check: it succeeds only while every io.onfhir dependency is
# genuinely published, never from a locally installed copy. It skips tests via -Pxtest,
# which is why it is not the release build. Same arguments apply:
#
#   docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r4 \
#     --build-arg "VERSION=$VERSION" --build-arg "REVISION=$REVISION" \
#     -t "srdc/repofyr:$VERSION-r4" -t srdc/repofyr:r4 .
