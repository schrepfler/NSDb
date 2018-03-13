package io.radicalbit.nsdb.actors

import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import io.radicalbit.nsdb.actors.ShardAccumulatorActor.Refresh
import io.radicalbit.nsdb.actors.ShardPerformerActor.PerformShardWrites
import io.radicalbit.nsdb.common.JSerializable
import io.radicalbit.nsdb.common.exception.InvalidStatementException
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement.{DescOrderOperator, Expression, SelectSQLStatement}
import io.radicalbit.nsdb.index.lucene._
import io.radicalbit.nsdb.index.{FacetIndex, NumericType, Schema, TimeSeriesIndex}
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import io.radicalbit.nsdb.statement.StatementParser._
import io.radicalbit.nsdb.statement.{StatementParser, TimeRangeExtractor}
import org.apache.lucene.index.{IndexNotFoundException, IndexWriter}
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.store.MMapDirectory
import spire.implicits._
import spire.math.Interval

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class ShardAccumulatorActor(basePath: String, db: String, namespace: String) extends Actor with ActorLogging {
  import scala.collection.mutable

  private val statementParser = new StatementParser()

  val shards: mutable.Map[ShardKey, TimeSeriesIndex] = mutable.Map.empty

  lazy val sharding: Boolean = context.system.settings.config.getBoolean("nsdb.sharding.enabled")

  val facetIndexShards: mutable.Map[ShardKey, FacetIndex] = mutable.Map.empty

  implicit val dispatcher: ExecutionContextExecutor = context.system.dispatcher

  var performerActor: ActorRef = _

  implicit val timeout: Timeout =
    Timeout(context.system.settings.config.getDuration("nsdb.publisher.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  lazy val interval = FiniteDuration(
    context.system.settings.config.getDuration("nsdb.write.scheduler.interval", TimeUnit.SECONDS),
    TimeUnit.SECONDS)

  private val opBufferMap: mutable.Map[String, ShardOperation] = mutable.Map.empty
  private var performingOps: Map[String, ShardOperation]       = Map.empty

  private def shardsForMetric(metric: String)        = shards.filter(_._1.metric == metric)
  private def facetsShardsFromMetric(metric: String) = facetIndexShards.filter(_._1.metric == metric)

  private def getIndex(key: ShardKey) =
    shards.getOrElse(
      key, {
        val directory =
          new MMapDirectory(Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}"))
        val newIndex = new TimeSeriesIndex(directory)
        shards += (key -> newIndex)
        newIndex
      }
    )

  private def getFacetIndex(key: ShardKey) =
    facetIndexShards.getOrElse(
      key, {
        val directory =
          new MMapDirectory(
            Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}", "facet"))
        val taxoDirectory = new MMapDirectory(
          Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}", "facet", "taxo"))
        val newIndex = new FacetIndex(directory, taxoDirectory)
        facetIndexShards += (key -> newIndex)
        newIndex
      }
    )

  private def handleQueryResults(metric: String, out: Try[Seq[Bit]]) = {
    out.recoverWith {
      case _: IndexNotFoundException => Success(Seq.empty)
    }
  }

  private def applyOrderingWithLimit(shardResult: Try[Seq[Bit]], statement: SelectSQLStatement, schema: Schema) = {
    Try(shardResult.get).map(s => {
      val maybeSorted = if (statement.order.isDefined) {
        val o = schema.fields.find(_.name == statement.order.get.dimension).get.indexType.ord
        implicit val ord: Ordering[JSerializable] =
          if (statement.order.get.isInstanceOf[DescOrderOperator]) o.reverse
          else o
        s.sortBy(_.fields(statement.order.get.dimension))
      } else s

      if (statement.limit.isDefined) maybeSorted.take(statement.limit.get.value) else maybeSorted
    })
  }

  override def preStart: Unit = {
    Option(Paths.get(basePath, db, namespace, "shards").toFile.list())
      .map(_.toSet)
      .getOrElse(Set.empty)
      .filter(_.split("_").length == 3)
      .map(_.split("_"))
      .foreach {
        case Array(metric, from, to) =>
          val directory =
            new MMapDirectory(Paths.get(basePath, db, namespace, "shards", s"${metric}_${from}_$to"))
          val newIndex = new TimeSeriesIndex(directory)
          shards += (ShardKey(metric, from.toLong, to.toLong) -> newIndex)
          val directoryFacets =
            new MMapDirectory(Paths.get(basePath, db, namespace, "shards", s"${metric}_${from}_$to", "facet"))
          val taxoDirectoryFacets =
            new MMapDirectory(Paths.get(basePath, db, namespace, "shards", s"${metric}_${from}_$to", "facet", "taxo"))
          val newFacetIndex = new FacetIndex(directoryFacets, taxoDirectoryFacets)
          facetIndexShards += (ShardKey(metric, from.toLong, to.toLong) -> newFacetIndex)
      }

    performerActor =
      context.actorOf(ShardPerformerActor.props(basePath, db, namespace), s"shard-performer-service-$db-$namespace")

    context.system.scheduler.schedule(0.seconds, interval) {
      if (opBufferMap.nonEmpty && performingOps.isEmpty) {
        performingOps = opBufferMap.toMap
        performerActor ! PerformShardWrites(performingOps)
      }
    }
  }

  def ddlOps: Receive = {
    case DeleteAllMetrics(_, ns) =>
      shards.foreach {
        case (_, index) =>
          implicit val writer: IndexWriter = index.getWriter
          index.deleteAll()
          writer.close()
          index.refresh()
      }
      facetIndexShards.foreach {
        case (k, index) =>
          implicit val writer: IndexWriter = index.getWriter
          index.deleteAll()
          writer.close()
          facetIndexShards -= k
      }
      sender ! AllMetricsDeleted(db, ns)
    case DropMetric(_, _, metric) =>
      shardsForMetric(metric).foreach {
        case (key, index) =>
          implicit val writer: IndexWriter = index.getWriter
          index.deleteAll()
          writer.close()
          index.refresh()
          shards -= key
      }
      facetsShardsFromMetric(metric).foreach {
        case (key, index) =>
          implicit val writer: IndexWriter = index.getWriter
          index.deleteAll()
          writer.close()
          index.refresh()
          facetIndexShards -= key
      }
      sender() ! MetricDropped(db, namespace, metric)
  }

  private def filterShardsThroughTime[T](expression: Option[Expression], indexes: mutable.Map[ShardKey, T]) = {
    val intervals = TimeRangeExtractor.extractTimeRange(expression)
    indexes.filter {
      case (key, _) if intervals.nonEmpty =>
        intervals
          .map(i => Interval.closed(key.from, key.to).intersect(i) != Interval.empty[Long])
          .foldLeft(false)((x, y) => x || y)
      case _ => true
    }.toSeq
  }

  private def groupShardResults[W](shardResults: Seq[Try[Seq[Bit]]], dimension: String)(mapFunction: Seq[Bit] => W) = {
    shardResults
      .flatMap(_.get)
      .groupBy(_.dimensions(dimension))
      .mapValues(mapFunction)
      .values
      .toSeq
  }

  private def orderPlainResults(statement: SelectSQLStatement,
                                parsedStatement: ParsedSimpleQuery,
                                indexes: Seq[(ShardKey, TimeSeriesIndex)],
                                schema: Schema) = {
    val (_, metric, q, _, limit, fields, sort) = ParsedSimpleQuery.unapply(parsedStatement).get
    if (statement.getTimeOrdering.isDefined || statement.order.isEmpty) {
      val result: ListBuffer[Try[Seq[Bit]]] = ListBuffer.empty

      val eventuallyOrdered =
        statement.getTimeOrdering.map(indexes.sortBy(_._1.from)(_)).getOrElse(indexes)

      eventuallyOrdered.takeWhile {
        case (_, index) =>
          val partials = handleQueryResults(metric, Try(index.query(q, fields, limit, sort)))
          result += partials

          val combined = Try(result.flatMap(_.get))

          combined.isSuccess && combined.get.lengthCompare(statement.limit.map(_.value).getOrElse(Int.MaxValue)) < 0
      }

      Try(result.flatMap(_.get))

    } else {

      val shardResults = indexes.map {
        case (_, index) =>
          handleQueryResults(metric, Try(index.query(q, fields, limit, sort)))
      }

      Try(shardResults.flatMap(_.get)).map(s => {
        val o = schema.fields.find(_.name == statement.order.get.dimension).get.indexType.ord
        implicit val ord: Ordering[JSerializable] =
          if (statement.order.get.isInstanceOf[DescOrderOperator]) o.reverse else o
        val sorted = s.sortBy(_.dimensions(statement.order.get.dimension))
        sorted.take(statement.limit.get.value)
      })

    }
  }

  def readOps: Receive = {
    case GetMetrics(_, _) =>
      sender() ! MetricsGot(db, namespace, shards.keys.map(_.metric).toSet)
    case GetCount(_, ns, metric) =>
      val hits = shardsForMetric(metric).map {
        case (_, index) =>
          index.query(new MatchAllDocsQuery(), Seq.empty, Int.MaxValue, None).size
      }.sum
      sender ! CountGot(db, ns, metric, hits)
    case ExecuteSelectStatement(statement, schema) =>
      val postProcessedResult: Try[Seq[Bit]] =
        statementParser.parseStatement(statement, schema) match {
          case Success(parsedStatement @ ParsedSimpleQuery(_, metric, q, false, limit, fields, sort)) =>
            val indexes =
              if (sharding)
                filterShardsThroughTime(statement.condition.map(_.expression), shardsForMetric(statement.metric))
              else Seq((ShardKey(metric, 0, 0), getIndex(ShardKey(metric, 0, 0))))

            val orderedResults = orderPlainResults(statement, parsedStatement, indexes, schema)

            if (fields.lengthCompare(1) == 0 && fields.head.count) {
              orderedResults.map(seq => {
                val recordCount = seq.map(_.value.asInstanceOf[Int]).sum
                val count       = if (recordCount <= limit) recordCount else limit
                Seq(Bit(0, count, Map(seq.head.dimensions.head._1 -> count)))
              })
            } else
              orderedResults.map(
                s =>
                  s.map(
                    b =>
                      if (b.dimensions.contains("count(*)")) b.copy(dimensions = b.dimensions + ("count(*)" -> s.size))
                      else b)
              )

          case Success(ParsedSimpleQuery(_, metric, q, true, limit, fields, sort)) if fields.lengthCompare(1) == 0 =>
            val distinctField = fields.head.name

            val filteredIndexes =
              filterShardsThroughTime(statement.condition.map(_.expression), facetsShardsFromMetric(statement.metric))

            val results = filteredIndexes.map {
              case (_, index) =>
                handleQueryResults(metric, Try(index.getDistinctField(q, fields.map(_.name).head, sort, limit)))
            }

            val shardResults = Try(groupShardResults(results, distinctField) { values =>
              Bit(0, 0, Map[String, JSerializable]((distinctField, values.head.dimensions(distinctField))))
            })

            applyOrderingWithLimit(shardResults, statement, schema)

          case Success(ParsedAggregatedQuery(_, metric, q, collector: CountAllGroupsCollector[_], sort, limit)) =>
            val result = filterShardsThroughTime(statement.condition.map(_.expression),
                                                 facetsShardsFromMetric(statement.metric)).map {
              case (_, index) =>
                handleQueryResults(
                  metric,
                  Try(index
                    .getCount(q, collector.groupField, sort, limit, schema.fieldsMap(collector.groupField).indexType)))
            }

            val shardResults = Try(groupShardResults(result, statement.groupBy.get) { values =>
              Bit(0, values.map(_.value.asInstanceOf[Long]).sum, values.head.dimensions)
            })

            applyOrderingWithLimit(shardResults, statement, schema)

          case Success(ParsedAggregatedQuery(_, metric, q, collector, sort, limit)) =>
            val shardResults = shardsForMetric(statement.metric).toSeq.map {
              case (_, index) =>
                handleQueryResults(metric, Try(index.query(q, collector.clear, limit, sort)))
            }
            val rawResult = Try(
              groupShardResults(shardResults, statement.groupBy.get) { values =>
                val v                                        = schema.fields.find(_.name == "value").get.indexType.asInstanceOf[NumericType[_, _]]
                implicit val numeric: Numeric[JSerializable] = v.numeric
                collector match {
                  case _: MaxAllGroupsCollector[_, _] =>
                    Bit(0, values.map(_.value).max, values.head.dimensions)
                  case _: MinAllGroupsCollector[_, _] =>
                    Bit(0, values.map(_.value).min, values.head.dimensions)
                  case _: SumAllGroupsCollector[_, _] =>
                    Bit(0, values.map(_.value).sum, values.head.dimensions)
                }
              }
            )

            applyOrderingWithLimit(rawResult, statement, schema)

          case Failure(ex) => Failure(ex)
          case _           => Failure(new InvalidStatementException("Not a select statement."))
        }

      postProcessedResult match {
        case Success(bits) => sender() ! SelectStatementExecuted(db, namespace, statement.metric, bits)
        case Failure(ex)   => sender() ! SelectStatementFailed(ex.getMessage)
      }
  }

  def accumulate: Receive = {
    case AddRecordToShard(_, ns, key, bit) =>
      opBufferMap += (UUID.randomUUID().toString -> WriteShardOperation(ns, key, bit))
      sender ! RecordAdded(db, ns, key.metric, bit)
    case DeleteRecordFromShard(_, ns, key, bit) =>
      opBufferMap += (UUID.randomUUID().toString -> DeleteShardRecordOperation(ns, key, bit))
      sender ! RecordDeleted(db, ns, key.metric, bit)
    case ExecuteDeleteStatementInShards(statement, schema, keys) =>
      statementParser.parseStatement(statement, schema) match {
        case Success(ParsedDeleteQuery(ns, metric, q)) =>
          keys.foreach { key =>
            opBufferMap += (UUID.randomUUID().toString -> DeleteShardQueryOperation(ns, key, q))
          }
          sender() ! DeleteStatementExecuted(db, namespace, metric)
        case Failure(ex) =>
          sender() ! DeleteStatementFailed(db = db, namespace = namespace, metric = statement.metric, ex.getMessage)
      }
    case Refresh(writeIds, keys) =>
      opBufferMap --= writeIds
      performingOps = Map.empty
      keys.foreach { key =>
        getIndex(key).refresh()
        getFacetIndex(key).refresh()
      }
  }

  override def receive: Receive = {
    readOps orElse ddlOps orElse accumulate
  }
}

object ShardAccumulatorActor {

  case class Refresh(writeIds: Seq[String], keys: Seq[ShardKey])

  def props(basePath: String, db: String, namespace: String): Props =
    Props(new ShardAccumulatorActor(basePath, db, namespace))
}
