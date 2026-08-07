# repofyr-dev-server

A runnable Repofyr server for development: it starts an embedded MongoDB, boots
the server for a chosen FHIR release against it, and stops the database again on
shutdown.

**Not a production artifact, and not published to Maven Central.** For anything
real, run a `repofyr-server-*` artifact against a MongoDB you operate.

## Running

The FHIR release is the first argument and defaults to R5:

```
mvn -pl repofyr-dev-server -am exec:java -Dexec.args=r4
```

Or build once and run the jar:

```
mvn -pl repofyr-dev-server -am package
java -jar repofyr-dev-server/target/repofyr-dev-server-standalone.jar stu3
```

Accepted releases are `r4`, `r5` and `stu3`. Anything else fails immediately
listing the valid values.

## What it does

1. Starts an embedded MongoDB at the address in `mongodb.host`, keeping its
   files in `./<server-name>.emb.mongo` so data survives a restart.
2. Boots Repofyr with the configurator for the chosen release.
3. Registers a shutdown callback through `Onfhir.apply(onShutdown = ...)`, so
   MongoDB stops after the HTTP binding has drained rather than racing it.

It does **not** consult `mongodb.embedded`. Starting the database is the whole
purpose of this module; that setting exists for the released servers, which
reject it.

## Configuration

It reads the same `application.conf` as any Repofyr server - the one packaged in
`repofyr-core`, overridable with `-Dconfig.file=...`. The only setting it treats
specially is `mongodb.host`, which must be `host:port`, because that is where it
puts the embedded instance.

## How one launcher boots three releases

It depends on all three server modules at once, which works because every
packaged resource carries its FHIR release in its name:
`definitions-r4.json.zip`, `conformance-statement-stu3.json`,
`db-index-conf-r5.json`, and so on. Nothing collides on the classpath, and
`application.conf` and `logback.xml` come from `repofyr-core` alone.

## Relationship to the other modules

Sits on top of everything: `repofyr-embedded-mongo` plus `repofyr-server-r4`,
`-r5` and `-stu3`. Nothing depends on it.

Because it is a development tool, its POM sets `maven.deploy.skip` and
`gpg.skip`; it is deliberately absent from the artifact table in
`scripts/check-staged-release.ps1`.
