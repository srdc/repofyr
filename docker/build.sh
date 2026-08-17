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

# Dockerfile-buildJar runs Maven inside the image instead, needing no local build. Because the
# builder starts with an empty Maven cache and can reach only Maven Central, it doubles as the
# reproducibility check: it succeeds only while every io.onfhir dependency is genuinely published,
# never from a locally installed copy. Before io.onfhir 4.0.0 reached Central it could not run at
# all; keep it that way rather than pointing it at a local repository.
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r4   -t srdc/repofyr:r4   .
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r5   -t srdc/repofyr:r5   .
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=stu3 -t srdc/repofyr:stu3 .
