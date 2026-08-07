# repofyr-embedded-mongo

Starts and stops an embedded MongoDB instance, for development and for tests.

```xml
<dependency>
    <groupId>io.repofyr</groupId>
    <artifactId>repofyr-embedded-mongo_2.13</artifactId>
    <version>4.0.0</version>
    <scope>test</scope>
</dependency>
```

## Why it is its own module

Through 3.x this lived in `onfhir-core` as `io.onfhir.db.EmbeddedMongo`, which
put it on the compile classpath of every server and inside every standalone
jar. It is a component whose job is downloading a `mongod` binary over the
network and executing it, so a secure health data repository should not carry it
in production.

In 4.0.0 it moved here, and no runnable `repofyr-server-*` artifact depends on
it. The consumers are `repofyr-dev-server`, which uses it to offer a one-command
development server, and the test suites, which declare it at test scope.

## Usage

```scala
import io.repofyr.embedded.EmbeddedMongo

EmbeddedMongo.start(
  appName = "my-app",
  host = "localhost",
  port = 27019,
  withTemporaryDatabaseDir = true)

// ... run something against localhost:27019 ...

EmbeddedMongo.stop()
```

`withTemporaryDatabaseDir = true` gives a throwaway database directory, which is
what tests want. `false` keeps the files in `./<appName>.emb.mongo` next to the
working directory, so a development instance survives a restart.

The first `start` downloads a MongoDB distribution and caches it under the
user's home directory, so it needs network access once. The process is a child
of the JVM and does not outlive it.

## Scope and non-goals

- Single node only. It cannot back `mongodb.transaction = true`, which needs a
  replica set or a sharded cluster.
- Not for production. `mongodb.embedded = true` against a `repofyr-server-*`
  artifact is rejected at startup by `io.repofyr.config.EmbeddedMongoSupport`,
  with a message pointing at `repofyr-dev-server`.
- No configuration reading. Host and port are parameters; the caller decides
  where they come from.

## Relationship to the other modules

Depends on nothing in the reactor - only flapdoodle and `slf4j-api` - so it is a
leaf. That matters: `repofyr-server-r4` needs it at test scope, while
`repofyr-dev-server` needs both it and the server modules. Had it lived in the
launcher, that pair of edges would have been a Maven reactor cycle.
