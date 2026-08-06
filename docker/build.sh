# Execute one of the following commands from the project.root.directory (../)

#Linux addJar
docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
docker build -f docker/Dockerfile-addJar --build-arg FHIR_VERSION=r5 -t srdc/repofyr:r5 .

#Windows addJar
docker build -f docker\Dockerfile-addJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
docker build -f docker\Dockerfile-addJar --build-arg FHIR_VERSION=r5 -t srdc/repofyr:r5 .

# buildJar
docker build -f docker/Dockerfile-buildJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
docker build -f docker\Dockerfile-buildJar --build-arg FHIR_VERSION=r4 -t srdc/repofyr:r4 .
