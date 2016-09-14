package com.datastax.spark.connector.rdd

import java.lang.{Long => JLong}

import scala.concurrent.Future
import com.datastax.spark.connector._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.embedded.SparkTemplate._
import com.datastax.spark.connector.embedded.YamlTransformations
import com.datastax.spark.connector.rdd.partitioner.EndpointPartition

case class KVRow(key: Int)

case class KVWithOptionRow(key: Option[Int])

case class FullRow(key: Int, group: Long, value: String)

case class NotWholePartKey(pk1: Int)

case class MissingClustering(pk1: Int, pk2: Int, pk3: Int, cc2: Int)

case class MissingClustering2(pk1: Int, pk2: Int, pk3: Int, cc3: Int, cc1: Int)

case class MissingClustering3(pk1: Int, pk2: Int, pk3: Int, cc1: Int, cc3: Int)

case class DataCol(pk1: Int, pk2: Int, pk3: Int, d1: Int)

class RDDSpec extends SparkCassandraITFlatSpecBase {
  useCassandraConfig(Seq(YamlTransformations.Default))
  useSparkConf(defaultConf.set("spark.cassandra.input.consistency.level", "ONE"))

  val conn = CassandraConnector(defaultConf)
  val tableName = "key_value"
  val otherTable = "other_table"
  val smallerTable = "smaller_table"
  val emptyTable = "empty_table"
  val wideTable = "wide_table"
  val manyColsTable = "many_cols_table"
  val keys = 0 to 200
  val smallerTotal = 0 to 10000 by 100
  val total = 0 to 10000

  conn.withSessionDo { session =>
    createKeyspace(session)

    awaitAll(
      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$tableName (
             |  key INT,
             |  group BIGINT,
             |  value TEXT,
             |  PRIMARY KEY (key, group)
             |)""".stripMargin)
        (for (value <- total) yield
          session.executeAsync(
            s"""INSERT INTO $ks.$tableName (key, group, value) VALUES (?, ?, ?)""",
            value: Integer,
            (value * 100).toLong: JLong,
            value.toString)
          ).par.foreach(_.getUninterruptibly)
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$smallerTable (
             |  key INT,
             |  group BIGINT,
             |  value TEXT,
             |  PRIMARY KEY (key, group)
             |)""".stripMargin)
        (for (value <- smallerTotal) yield
          session.executeAsync(
            s"""INSERT INTO $ks.$smallerTable (key, group, value) VALUES (?, ?, ?)""",
            value: Integer,
            (value * 100).toLong: JLong,
            value.toString)
          ).par.foreach(_.getUninterruptibly)
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$emptyTable (
             |  key INT,
             |  group BIGINT,
             |  value TEXT,
             |  PRIMARY KEY (key, group)
             |)""".stripMargin)
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$otherTable (key INT, group BIGINT,  PRIMARY KEY (key))
             |""".stripMargin)
        (for (value <- keys) yield
          session.executeAsync(
            s"""INSERT INTO $ks.$otherTable (key, group) VALUES (?, ?)""",
            value: Integer,
            (value * 100).toLong: JLong)
          ).par.foreach(_.getUninterruptibly)
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$wideTable (
             |  key INT,
             |  group BIGINT,
             |  value TEXT,
             |  PRIMARY KEY (key, group)
             |)""".stripMargin)
        (for (value <- keys; cconeValue <- value * 100 until value * 100 + 5) yield
          session.executeAsync(
            s"""INSERT INTO $ks.$wideTable (key, group, value) VALUES (?, ?, ?)""",
            value: Integer,
            cconeValue.toLong: JLong,
            value.toString)
          ).par.foreach(_.getUninterruptibly)
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.$manyColsTable (
             |  pk1 int,
             |  pk2 int,
             |  pk3 int,
             |  cc1 int,
             |  cc2 int,
             |  cc3 int,
             |  cc4 int,
             |  d1 int,
             |  PRIMARY KEY ((pk1, pk2, pk3), cc1, cc2, cc3, cc4)
             |)""".stripMargin)
      }
    )
  }

  def checkLeftSide[T, S](leftSideSource: Array[T], result: Array[(T, S)]) = {
    markup("Checking LeftSide")
    val leftSideResults = result.map(_._1)
    for (element <- leftSideSource) {
      leftSideResults should contain(element)
    }
  }

  def checkArrayCassandraRow[T](result: Array[(T, CassandraRow)]) = {
    markup("Checking RightSide Join Results")
    result.length should be(keys.length)
    for (key <- keys) {
      val sorted_result = result.map(_._2).sortBy(_.getInt(0))
      sorted_result(key).getInt("key") should be(key)
      sorted_result(key).getLong("group") should be(key * 100)
      sorted_result(key).getString("value") should be(key.toString)
    }
  }

  def checkArrayOptionCassandraRow[T](result: Array[(T, Option[CassandraRow])]) = {
    markup("Checking RightSide LeftJoin Results")
    result.length should be(keys.length)
    val sorted_result = result
      .map(_._2)
      .filter(_.isDefined)
      .sortBy(_.get.getInt(0))
    for (key <- keys) {
      sorted_result(key).get.getInt("key") should be(key)
      sorted_result(key).get.getLong("group") should be(key * 100)
      sorted_result(key).get.getString("value") should be(key.toString)
    }
  }

  def checkArrayOptionSmallerCassandraRow[T](result: Array[(T, Option[CassandraRow])]) = {
    markup("Checking RightSide LeftJoin Results")
    result.length should be(keys.length)
    val sorted_result = result
      .map(_._2)
      .filter(_.isDefined)
      .sortBy(_.get.getInt(0))
    for ((key, idx) <- keys.intersect(smallerTotal).zipWithIndex) {
      sorted_result(idx).get.getInt("key") should be(key)
      sorted_result(idx).get.getLong("group") should be(key * 100)
      sorted_result(idx).get.getString("value") should be(key.toString)
    }
  }

  def checkArrayOptionEmptyCassandraRow[T](result: Array[(T, Option[CassandraRow])]) = {
    markup("Checking Empty RightSide LeftJoin Results")
    result.length should be(keys.length)
    for (key <- keys) {
      result(key)._2 should be(None)
    }
  }

  def checkArrayTuple[T](result: Array[(T, (Int, Long, String))]) = {
    markup("Checking RightSide Join Results")
    result.length should be(keys.length)
    for (key <- keys) {
      val sorted_result = result.map(_._2).sortBy(_._1)
      sorted_result(key)._1 should be(key)
      sorted_result(key)._2 should be(key * 100)
      sorted_result(key)._3 should be(key.toString)
    }
  }

  def checkArrayFullRow[T](result: Array[(T, FullRow)]) = {
    markup("Checking RightSide Join Results")
    result.length should be(keys.length)
    for (key <- keys) {
      val sorted_result = result.map(_._2).sortBy(_.key)
      sorted_result(key).key should be(key)
      sorted_result(key).group should be(key * 100)
      sorted_result(key).value should be(key.toString)
    }
  }

  "A Tuple RDD specifying partition keys" should "be joinable with Cassandra" in {
    val source = sc.parallelize(keys).map(Tuple1(_))
    val someCass = source.joinWithCassandraTable(ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a tuple from Cassandra" in {
    val source = sc.parallelize(keys).map(Tuple1(_))
    val someCass = source.joinWithCassandraTable[(Int, Long, String)](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayTuple(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a case class from cassandra" in {
    val source = sc.parallelize(keys).map(Tuple1(_))
    val someCass = source.joinWithCassandraTable[FullRow](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayFullRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be repartitionable" in {
    val source = sc.parallelize(keys).map(Tuple1(_))
    val repart = source.repartitionByCassandraReplica(ks, tableName, 10)
    repart.partitions.length should be(conn.hosts.size * 10)
    val someCass = repart.joinWithCassandraTable(ks, tableName)
    someCass.partitions.foreach {
      case e: EndpointPartition =>
        conn.hosts should contain(e.endpoints.head)
      case _ =>
        fail("Unable to get endpoints on repartitioned RDD, This means preferred locations will be broken")
    }
    val result = someCass.collect
    checkArrayCassandraRow(result)
  }

  it should "be deterministically repartitionable" in {
    val source = sc.parallelize(keys).map(Tuple1(_))
    val repartRDDs = (1 to 10).map(_ =>
      source
        .repartitionByCassandraReplica(ks, tableName, 10)
        .mapPartitionsWithIndex((index, it) => it.map((_, index))))
    val first = repartRDDs(1).collect
    repartRDDs.foreach( rdd => rdd.collect should be(first))
  }

  "A case-class RDD specifying partition keys" should "be retrievable from Cassandra" in {
    val source = sc.parallelize(keys).map(x => new KVRow(x))
    val someCass = source.joinWithCassandraTable(ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a tuple from Cassandra" in {
    val source = sc.parallelize(keys).map(x => new KVRow(x))
    val someCass = source.joinWithCassandraTable[(Int, Long, String)](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayTuple(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a case class from cassandra" in {
    val source = sc.parallelize(keys).map(x => new KVRow(x))
    val someCass = source.joinWithCassandraTable[FullRow](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayFullRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be repartitionable" in {
    val source = sc.parallelize(keys).map(x => new KVRow(x))
    val repart = source.repartitionByCassandraReplica(ks, tableName, 10)
    repart.partitions.length should be(conn.hosts.size * 10)
    val someCass = repart.joinWithCassandraTable(ks, tableName)
    someCass.partitions.foreach {
      case e: EndpointPartition =>
        conn.hosts should contain(e.endpoints.head)
      case _ =>
        fail("Unable to get endpoints on repartitioned RDD, This means preferred locations will be broken")
    }
    val result = someCass.collect
    checkArrayCassandraRow(result)
  }

  it should "throw a meaningful exception if partition column is null when joining with Cassandra table" in withoutLogging {
    val source = sc.parallelize(keys).map(x ⇒ new KVWithOptionRow(None))
    val ex = the[Exception] thrownBy source.joinWithCassandraTable[(Int, Long, String)](ks, tableName).collect()
    ex.getMessage.toLowerCase should include("invalid null value")
    ex.getMessage.toLowerCase should include("key")
  }

  it should "throw a meaningful exception if partition column is null when repartitioning by replica" in withoutLogging {
    val source = sc.parallelize(keys).map(x ⇒ (None: Option[Int], x * 100: Long))
    val ex = the[Exception] thrownBy source.repartitionByCassandraReplica(ks, tableName, 10).collect()
    ex.getMessage should include("Invalid null value for key column key")
  }

  it should "throw a meaningful exception if partition column is null when saving" in withoutLogging {
    val source = sc.parallelize(keys).map(x ⇒ (None: Option[Int], x * 100: Long, ""))
    val ex = the[Exception] thrownBy source.saveToCassandra(ks, tableName)
    ex.getMessage should include("Invalid null value for key column key")
  }

  "A Tuple RDD specifying partitioning keys and clustering keys " should "be retrievable from Cassandra" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100: Long))
    val someCass = source.joinWithCassandraTable(ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a tuple from Cassandra" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100: Long))
    val someCass = source.joinWithCassandraTable[(Int, Long, String)](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayTuple(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a case class from cassandra" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100: Long))
    val someCass = source.joinWithCassandraTable[FullRow](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayFullRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be repartitionable" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100: Long))
    val repart = source.repartitionByCassandraReplica(ks, tableName, 10)
    repart.partitions.length should be(conn.hosts.size * 10)
    val someCass = repart.joinWithCassandraTable(ks, tableName)
    someCass.partitions.foreach {
      case e: EndpointPartition =>
        conn.hosts should contain(e.endpoints.head)
      case _ =>
        fail("Unable to get endpoints on repartitioned RDD, This means preferred locations will be broken")
    }
    val result = someCass.collect
    checkArrayCassandraRow(result)
  }

  it should "use the ReadConf from the SparkContext by default" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100: Long))
    val someCass = source.joinWithCassandraTable[FullRow](ks, tableName)
    someCass.readConf.consistencyLevel should be (com.datastax.driver.core.ConsistencyLevel.ONE)
  }

  it should "be joinable on both partitioning key and clustering key" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100))
    val someCass = source.joinWithCassandraTable(ks, wideTable, joinColumns = SomeColumns("key", "group"))
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be joinable on both partitioning key and clustering key using on" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100))
    val someCass = source.joinWithCassandraTable(ks, wideTable).on(SomeColumns("key", "group"))
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be be able to be limited" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100))
    val someCass = source.joinWithCassandraTable(ks, wideTable).on(SomeColumns("key", "group")).limit(3)
    val result = someCass.collect
    result should have size (3 * someCass.partitions.size)
  }

  it should "have be able to be counted" in {
    val source = sc.parallelize(keys).map(x => (x, x * 100))
    val someCass = source.joinWithCassandraTable(ks, wideTable).on(SomeColumns("key", "group")).cassandraCount()
    someCass should be(201)

  }

  "A CassandraRDD " should "be joinable with Cassandra" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.joinWithCassandraTable(ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be left joinable with Cassandra" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.leftJoinWithCassandraTable(ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayOptionCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be left joinable with a table of a smaller size" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.leftJoinWithCassandraTable(ks, smallerTable)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayOptionSmallerCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be left joinable with an empty table" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.leftJoinWithCassandraTable(ks, emptyTable)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayOptionEmptyCassandraRow(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a tuple from Cassandra" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.joinWithCassandraTable[(Int, Long, String)](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayTuple(result)
    checkLeftSide(leftSide, result)
  }

  it should "be retrievable as a case class from cassandra" in {
    val source = sc.cassandraTable(ks, otherTable)
    val someCass = source.joinWithCassandraTable[FullRow](ks, tableName)
    val result = someCass.collect
    val leftSide = source.collect
    checkArrayFullRow(result)
    checkLeftSide(leftSide, result)
  }

  it should " be retrievable without repartitioning" in {
    val someCass = sc.cassandraTable(ks, otherTable).joinWithCassandraTable(ks, tableName)
    someCass.toDebugString should not contain "ShuffledRDD"
    val result = someCass.collect
    checkArrayCassandraRow(result)
  }

  it should "be repartitionable" in {
    val source = sc.cassandraTable(ks, otherTable)
    val repart = source.repartitionByCassandraReplica(ks, tableName, 10)
    repart.partitions.length should be(conn.hosts.size * 10)
    val someCass = repart.joinWithCassandraTable(ks, tableName)
    someCass.partitions.foreach {
      case e: EndpointPartition =>
        conn.hosts should contain(e.endpoints.head)
      case _ =>
        fail("Unable to get endpoints on repartitioned RDD, This means preferred locations will be broken")
    }
    val result = someCass.collect
    checkArrayCassandraRow(result)
  }

  "A Joined CassandraRDD " should " support select clauses " in {
    val someCass = sc.cassandraTable(ks, otherTable).joinWithCassandraTable(ks, tableName).select("value")
    val results = someCass.collect.map(_._2).map(_.getInt("value")).sorted
    results should be(keys.toArray)
  }

  it should " support where clauses" in {
    val someCass = sc.parallelize(keys).map(x => new KVRow(x)).joinWithCassandraTable(ks, tableName).where("group >= 500")
    val results = someCass.collect.map(_._2)
    results should have length keys.count(_ >= 5)
  }

  it should " support parametrized where clauses" in {
    val someCass = sc.parallelize(keys).map(x => new KVRow(x)).joinWithCassandraTable(ks, tableName).where("group >= ?", 500L)
    val results = someCass.collect.map(_._2)
    results should have length keys.count(_ >= 5)
  }

  it should " throw an exception if using a where on a column that is specified by the join" in {
    val exc = intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys).map(x => (x, x * 100L))
        .joinWithCassandraTable(ks, tableName)
        .where("group >= ?", 500L)
        .on(SomeColumns("key", "group"))
      val results = someCass.collect.map(_._2)
      results should have length keys.count(_ >= 5)
    }
  }

  it should " throw an exception if using a where on a column that is a part of the Partition key" in {
    val exc = intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new KVRow(x))
        .joinWithCassandraTable(ks, tableName)
        .where("key = 200")
        .collect()
    }
  }

  it should " throw an exception if you don't have all Partition Keys available" in {
    intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new NotWholePartKey(x))
        .joinWithCassandraTable(ks, manyColsTable)
        .collect()
    }
  }

  it should " throw an exception if you try to join on later clustering columns without earlier ones" in {
    intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new MissingClustering(x, x, x, x))
        .joinWithCassandraTable(ks, manyColsTable)
        .on(SomeColumns("pk1", "pk2", "pk3", "cc2"))
        .collect()
    }
  }

  it should " throw an exception if you try to join on later clustering columns without earlier ones even when out of order" in {
    intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new MissingClustering2(x, x, x, x, x))
        .joinWithCassandraTable(ks, manyColsTable)
        .on(SomeColumns("pk1", "pk2", "pk3", "cc3", "cc1"))
        .collect()
    }
  }

  it should " throw an exception if you try to join on later clustering columns without earlier ones even when reversed" in {
    intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new MissingClustering3(x, x, x, x, x))
        .joinWithCassandraTable(ks, manyColsTable)
        .on(SomeColumns("pk1", "pk2", "pk3", "cc1", "cc3"))
        .collect()
    }
  }

  it should " throw an exception if you try to join with a data column" in {
    intercept[IllegalArgumentException] {
      val someCass = sc.parallelize(keys)
        .map(x => new DataCol(x, x, x, x))
        .joinWithCassandraTable(ks, manyColsTable)
        .on(SomeColumns("pk1", "pk2", "pk3", "d1")).collect()
    }
  }

  it should "allow to use empty RDD on undefined table" in {
    val result = sc.parallelize(keys)
      .joinWithCassandraTable("unknown_ks", "unknown_table")
      .toEmptyCassandraRDD
      .collect()
    result should have length 0
  }

  it should "allow to use empty RDD on defined table" in {
    val result = sc.parallelize(keys)
      .joinWithCassandraTable(ks, manyColsTable)
      .toEmptyCassandraRDD
      .collect()
    result should have length 0
  }

  it should " be lazy and not throw an exception if the table is not found at initializaiton time" in {
    val someCass = sc.parallelize(keys).map(x => new DataCol(x, x, x, x)).joinWithCassandraTable("unknown_keyspace", "unknown_table")
  }


}
