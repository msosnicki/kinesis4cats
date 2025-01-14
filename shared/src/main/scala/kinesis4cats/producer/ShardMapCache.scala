/*
 * Copyright 2023-2023 etspaceman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kinesis4cats
package producer

import scala.concurrent.duration._

import java.nio.charset.StandardCharsets
import java.time.Instant

import cats.Show
import cats.effect.kernel.Resource
import cats.effect.syntax.all._
import cats.effect.{Async, Ref}
import cats.syntax.all._
import org.typelevel.log4cats.StructuredLogger

import kinesis4cats.Utils
import kinesis4cats.logging.{LogContext, LogEncoder}
import kinesis4cats.models._

/** A cache of shards for a stream, which can be used to predict the shard ID
  * for a given record's partition key
  *
  * @param config
  *   [[kinesis4cats.producer.ShardMapCache.Config ShardMapCache.Config]]
  * @param logger
  *   [[org.typelevel.log4cats.StructuredLogger StructuredLogger]]
  * @param shardMapRef
  *   [[cats.effect.Ref Ref]] of [[kinesis4cats.producer.ShardMap ShardMap]]
  * @param shardMapF
  *   F that supplies a new [[kinesis4cats.producer.ShardMap ShardMap]]
  * @param F
  *   [[cats.effect.Async Async]]
  * @param encoders
  *   [[kinesis4cats.producer.ShardMapCache.LogEncoders ShardMapCache.LogEncoders]]
  */
private[kinesis4cats] class ShardMapCache[F[_]] private (
    config: ShardMapCache.Config,
    logger: StructuredLogger[F],
    shardMapRef: Ref[F, ShardMap],
    shardMapF: F[Either[ShardMapCache.Error, ShardMap]],
    encoders: ShardMapCache.LogEncoders
)(implicit F: Async[F]) {
  import encoders._

  /** Predicts a shard that a record will land on given its partition key
    *
    * @param partitionKey
    *   The partition key for the record
    * @return
    *   Either a
    *   [[kinesis4cats.producer.ShardMapCache.Error ShardMapCache.Error]] or
    *   [[kinesis4cats.models.ShardId ShardId]]
    */
  def shardForPartitionKey(
      partitionKey: String
  ): F[Either[ShardMapCache.Error, ShardId]] =
    shardMapRef.get.map(_.shardForPartitionKey(partitionKey))

  /** Refresh the shard cache by running shardMapF
    */
  def refresh(): F[Either[ShardMapCache.Error, Unit]] = {
    val ctx = LogContext()
    for {
      newMap <- shardMapF
      res <- newMap.bitraverse(
        e =>
          logger
            .error(ctx.context, e)("Error retrieving newest shard map")
            .as(e),
        x =>
          for {
            _ <- logger.debug(ctx.context)(
              "Successfully retrieved new shard map"
            )
            _ <- logger.trace(ctx.addEncoded("shardMap", x).context)(
              "Logging shard map"
            )
            _ <- shardMapRef.set(x)
          } yield ()
      )
    } yield res
  }

  /** Start the cache
    */
  private def start() = for {
    _ <- refresh().toResource
    _ <- F
      .sleep(config.refreshInterval)
      .flatMap(_ => refresh())
      .foreverM
      .background
      .void
  } yield ()

}

object ShardMapCache {

  final case class Builder[F[_]] private (
      config: Config,
      shardMapF: F[Either[Error, ShardMap]],
      logger: StructuredLogger[F],
      encoders: LogEncoders
  )(implicit F: Async[F]) {
    def withConfig(config: Config): Builder[F] = copy(config = config)
    def withShardMapF(shardMapF: F[Either[Error, ShardMap]]): Builder[F] =
      copy(shardMapF = shardMapF)
    def withLogger(logger: StructuredLogger[F]): Builder[F] =
      copy(logger = logger)
    def withLogEncoders(encoders: LogEncoders): Builder[F] =
      copy(encoders = encoders)

    def build: Resource[F, ShardMapCache[F]] = for {
      ref <- Ref.of[F, ShardMap](ShardMap.empty).toResource
      service = new ShardMapCache[F](config, logger, ref, shardMapF, encoders)
      _ <- service.start()
    } yield service
  }

  object Builder {
    def default[F[_]](
        shardMapF: F[Either[Error, ShardMap]],
        logger: StructuredLogger[F]
    )(implicit
        F: Async[F]
    ): Builder[F] = Builder[F](
      Config.default,
      shardMapF,
      logger,
      LogEncoders.show
    )

    @annotation.unused
    private def unapply[F[_]](builder: Builder[F]): Unit = ()
  }

  /** [[kinesis4cats.logging.LogEncoder LogEncoder]] instances for the
    * ShardMapCache
    *
    * @param shardMapLogEncoder
    *   [[kinesis4cats.logging.LogEncoder LogEncoder]] instance for
    *   [[kinesis4cats.producer.ShardMap]]
    */
  final class LogEncoders(implicit val shardMapLogEncoder: LogEncoder[ShardMap])

  object LogEncoders {
    val show: LogEncoders = {
      import kinesis4cats.logging.instances.show._

      implicit val hashKeyRangeShow: Show[HashKeyRange] = x =>
        ShowBuilder("HashKeyRange")
          .add("endingHashKey", x.endingHashKey)
          .add("startingHashKey", x.startingHashKey)
          .build

      implicit val shardMapRecordShow: Show[ShardMapRecord] = x =>
        ShowBuilder("ShardMapRecord")
          .add("shardId", x.shardId)
          .add("hashKeyRange", x.hashKeyRange)
          .build

      implicit val shardMapShow: Show[ShardMap] = x =>
        ShowBuilder("ShardMap")
          .add("lastUpdated", x.lastUpdated)
          .add("shards", x.shards)
          .build

      new LogEncoders()
    }
  }

  /** Configuration for the ShardMapCache
    *
    * @param refreshInterval
    *   How often to refresh the shard cache
    */
  final case class Config(refreshInterval: FiniteDuration)

  object Config {

    /** Default configuration for the ShardMapCache
      */
    val default = Config(1.hour)
  }

  /** Errors that can be received in the ShardMapCache
    *
    * @param msg
    *   Error message
    */
  sealed abstract class Error(msg: String) extends Exception(msg)

  /** Error for when the partition key cannot be matched to a shard
    *
    * @param partitionKey
    *   partition key that was not matched
    */
  final case class ShardForPartitionKeyNotFound(partitionKey: String)
      extends Error(s"Could not find shard for partition key ${partitionKey}")

  /** Error for when the cache could not list the shards
    *
    * @param e
    *   Underlying error
    */
  final case class ListShardsError(e: Throwable) extends Error(e.getMessage())

}

final case class ShardMap(shards: List[ShardMapRecord], lastUpdated: Instant) {
  def shardForPartitionKey(
      partitionKey: String
  ): Either[ShardMapCache.Error, ShardId] = {
    val hashBytes = Utils.md5(partitionKey.getBytes(StandardCharsets.UTF_8))
    val hashKey = BigInt.apply(1, hashBytes)
    ShardMap.findShard(partitionKey, hashKey, shards)
  }
}

object ShardMap {
  @annotation.tailrec
  def findShard(
      partitionKey: String,
      hashKey: BigInt,
      shards: List[ShardMapRecord]
  ): Either[ShardMapCache.Error, ShardId] = shards match {
    case Nil => Left(ShardMapCache.ShardForPartitionKeyNotFound(partitionKey))
    case h :: t =>
      if (h.hashKeyRange.isBetween(hashKey)) Right(h.shardId)
      else findShard(partitionKey, hashKey, t)
  }

  def empty = ShardMap(List.empty, Instant.now())

}

final case class ShardMapRecord(shardId: ShardId, hashKeyRange: HashKeyRange)
