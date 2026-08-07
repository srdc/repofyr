package io.repofyr.db


import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.connection.{ClusterSettings, ConnectionPoolSettings}
import org.mongodb.scala.{ClientSession, MongoClient, MongoClientSettings, MongoCollection, MongoCredential, MongoDatabase, Observable, ReadConcern, ReadPreference, ServerAddress, TransactionOptions, WriteConcern}

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
  * MongoDB client object
  */
object MongoDB {
  implicit val executionContext: ExecutionContextExecutor = Onfhir.actorSystem.dispatcher

  private val SYSTEM_INDEXES = "system.indexes"

  private val dbHosts = OnfhirConfig.mongoDbSettings.hosts

  private val writeConcern:WriteConcern = OnfhirConfig.mongoDbSettings.writeConcern match {
    case "1" | "2" | "3" => WriteConcern.apply(OnfhirConfig.mongoDbSettings.writeConcern.toInt)
    case oth => WriteConcern.apply(oth)
  }

  val transactionOptions:TransactionOptions =
    TransactionOptions
    .builder()
    .readPreference(ReadPreference.primary())
    .readConcern(ReadConcern.LOCAL)
    .writeConcern(writeConcern)
    .build()


  // Base connection settings for MongoDB
  private val mongoClient: MongoClient = {
    var clientSettingsBuilder = MongoClientSettings.builder()
    //Set hostnames
    clientSettingsBuilder = clientSettingsBuilder
      .applicationName("onFhir.io")
      .applyToClusterSettings(b => b.applySettings(
        ClusterSettings
          .builder()
          .hosts(dbHosts.toList.map(h => new ServerAddress(h)).asJava)
          //.mode(if(dbHosts.length > 1) ClusterConnectionMode.MULTIPLE else ClusterConnectionMode.SINGLE)
          .build()
      ))

    //If database is secure
    if (OnfhirConfig.mongoDbSettings.username.isDefined && OnfhirConfig.mongoDbSettings.password.isDefined && OnfhirConfig.mongoDbSettings.authDbName.isDefined){
      clientSettingsBuilder = clientSettingsBuilder.credential(MongoCredential.createCredential(OnfhirConfig.mongoDbSettings.username.get, OnfhirConfig.mongoDbSettings.authDbName.get, OnfhirConfig.mongoDbSettings.password.get.toCharArray))
    }

    //If pooling is configured
    OnfhirConfig.mongoDbSettings.pooling.foreach { pooling =>
      clientSettingsBuilder = clientSettingsBuilder.applyToConnectionPoolSettings( b => b.applySettings(
          ConnectionPoolSettings
            .builder()
            .minSize(pooling.minSize.getOrElse(5))
            .maxSize(pooling.maxSize.getOrElse(200))
            .maxWaitTime(pooling.maxWaitTime.getOrElse(180L), TimeUnit.SECONDS) //3 minutes default
            .maxConnectionLifeTime(pooling.maxConnectionLifeTime.getOrElse(1200L), TimeUnit.SECONDS) // 20 minutes default
            .build()
          )
        )
    }

    MongoClient(clientSettingsBuilder.build())
  }

  /*if (OnfhirConfig.mongoDbSettings.username.isDefined && OnfhirConfig.mongoDbSettings.password.isDefined && OnfhirConfig.mongoDbSettings.authDbName.isDefined) {
    val username = OnfhirConfig.mongoDbSettings.username.get
    val password = OnfhirConfig.mongoDbSettings.password.get
    val authdb = OnfhirConfig.mongoDbSettings.authDbName.get
    MongoClient(s"mongodb://$username:$password@${dbHosts.mkString(",")}/?authSource=$authdb")
  } else {
    MongoClient(s"mongodb://${dbHosts.mkString(",")}")
  }*/


  //FHIR database
  private val database: MongoDatabase = mongoClient.getDatabase(OnfhirConfig.mongoDbSettings.dbName)


  /**
    * Get the database
    * @return
    */
  def getDatabase: MongoDatabase = database

  /**
    * Create a transaction session
    * @return
    */
  def createSession():Observable[ClientSession] = mongoClient.startSession()

  /**
    * Get a specific collection for current version of FHIR resources of a specific FHIR Resource type (e.g. Observation)
    * @param name Name of collection
    * @return
    */
  def getCollection(name: String, history:Boolean = false): MongoCollection[Document] = if(history) database.getCollection(name+"_history") else database.getCollection(name)

  /**
    * Enable sharding on FHIR database
    * @return
    */
  def enableSharding():Future[Document] = {
    mongoClient
      .getDatabase(OnfhirConfig.mongoDbSettings.authDbName.getOrElse("admin")) //This should run on admin database
      .runCommand(Document("enableSharding" -> OnfhirConfig.mongoDbSettings.dbName)).toFuture()
  }

  /**
    * Shard a collection
    * @param collectionName Name of the collection
    * @param key The field (or index name for compound) name to be used for sharding
    * @return
    */
  def shardCollection(collectionName:String, key:String):Future[Document] = {
    mongoClient
      .getDatabase(OnfhirConfig.mongoDbSettings.authDbName.getOrElse("admin")) //This should run on admin database
      .runCommand(Document("shardCollection" -> s"${OnfhirConfig.mongoDbSettings.dbName}.$collectionName", "key" -> Document(key -> "hashed"))).toFuture()
  }

  /**
    * Refresh Mongo configs on config servers
    * @return
    */
  def refreshDBConfig():Future[Unit] ={
    Future.sequence(
      OnfhirConfig.mongoDbSettings.hosts.map( _ =>
        mongoClient
          .getDatabase(OnfhirConfig.mongoDbSettings.authDbName.getOrElse("admin"))
          .runCommand(Document("flushRouterConfig" -> OnfhirConfig.mongoDbSettings.dbName))
          .toFuture()
      )
    ).map(docs =>
      ()
    )
  }

  /**
    * List collections
    */
  def listCollections(history:Boolean = false):Future[Seq[String]] = {
    if(history)
      listHistoryCollections()
    else
      listCurrentCollections()
  }

  /**
    * List all FHIR current collections in FHIR database
    * @return
    */
  private def listCurrentCollections():Future[Seq[String]] = database.listCollectionNames().toFuture() map { list =>
    list.filterNot(collection => collection == SYSTEM_INDEXES || collection.endsWith("_history"))
  }

  /**
    * List all collections created for string FHIR history resources for each resource type
    * @return
    */
  private def listHistoryCollections():Future[Seq[String]] = database.listCollectionNames().toFuture() map { list =>
    list.filter(collection => collection.endsWith("_history")).map(_.replace("_history", ""))
  }
}
