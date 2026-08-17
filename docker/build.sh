# Reference build commands. Run these from the repository root, not from docker/, because both
# Dockerfiles take the repository as their build context.
#
# Forward slashes in -f work on Windows too, so there is one set of commands rather than one per
# platform.

# Dockerfile-addJar packages an already-built shaded jar, so build it first:
#   mvn package -pl repofyr-server-r4 -am
docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r4   -t srdc/repofyr:r4   .
docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r5   -t srdc/repofyr:r5   .
docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=stu3 -t srdc/repofyr:stu3 .

# Dockerfile-buildJar runs Maven inside the image instead, needing no local build.
#
# It does NOT work yet, and not because of anything in this file: the builder image starts with an
# empty Maven cache and can reach only Maven Central, where io.onfhir 4.0.0 is not published. The
# build fails resolving io.onfhir:onfhir-common_2.13:jar:4.0.0 in repofyr-event. A local `mvn
# package` succeeds only because ~/.m2 holds a locally installed copy the container cannot see.
# Use the addJar commands above until the libraries are released; see entry 14 of
# docs/release/known-limitations.md.
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r4   -t srdc/repofyr:r4   .
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r5   -t srdc/repofyr:r5   .
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=stu3 -t srdc/repofyr:stu3 .
