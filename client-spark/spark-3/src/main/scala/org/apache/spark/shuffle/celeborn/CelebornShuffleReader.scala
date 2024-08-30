/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.celeborn

import java.io.IOException
import java.util
import java.util.concurrent.{ConcurrentHashMap, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import org.apache.spark.{Aggregator, InterruptibleIterator, ShuffleDependency, TaskContext}
import org.apache.spark.celeborn.ExceptionMakerHelper
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.shuffle.{FetchFailedException, ShuffleReadMetricsReporter, ShuffleReader}
import org.apache.spark.shuffle.celeborn.CelebornShuffleReader.streamCreatorPool
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.collection.ExternalSorter
import org.apache.celeborn.client.ShuffleClient
import org.apache.celeborn.client.read.{CelebornInputStream, MetricsCallback}
import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.exception.{CelebornIOException, PartitionUnRetryAbleException}
import org.apache.celeborn.common.network.client.TransportClient
import org.apache.celeborn.common.network.protocol.TransportMessage
import org.apache.celeborn.common.protocol.{MessageType, PartitionLocation, PbOpenStreamList, PbOpenStreamListResponse, PbStreamHandler}
import org.apache.celeborn.common.protocol.message.StatusCode
import org.apache.celeborn.common.util.{JavaUtils, ThreadUtils, Utils}

import scala.collection.Iterator
import scala.reflect.ClassTag.Any

class CelebornShuffleReader[K, C](
    handle: CelebornShuffleHandle[K, _, C],
    startPartition: Int,
    endPartition: Int,
    startMapIndex: Int = 0,
    endMapIndex: Int = Int.MaxValue,
    context: TaskContext,
    conf: CelebornConf,
    metrics: ShuffleReadMetricsReporter,
    shuffleIdTracker: ExecutorShuffleIdTracker)
  extends ShuffleReader[K, C] with Logging {

  private val dep = handle.dependency
  private val shuffleClient = ShuffleClient.get(
    handle.appUniqueId,
    handle.lifecycleManagerHost,
    handle.lifecycleManagerPort,
    conf,
    handle.userIdentifier,
    handle.extension)

  private val exceptionRef = new AtomicReference[IOException]
  private val throwsFetchFailure = handle.throwsFetchFailure

  override def read(): Iterator[Product2[K, C]] = {

    val serializerInstance = newSerializerInstance(dep)

    val shuffleId = SparkUtils.celebornShuffleId(shuffleClient, handle, context, false)
    shuffleIdTracker.track(handle.shuffleId, shuffleId)
    logDebug(
      s"get shuffleId $shuffleId for appShuffleId ${handle.shuffleId} attemptNum ${context.stageAttemptNumber()}")

    // Update the context task metrics for each record read.
    val metricsCallback = new MetricsCallback {
      override def incBytesRead(bytesWritten: Long): Unit = {
        metrics.incRemoteBytesRead(bytesWritten)
        metrics.incRemoteBlocksFetched(1)
      }

      override def incReadTime(time: Long): Unit =
        metrics.incFetchWaitTime(time)
    }

    if (streamCreatorPool == null) {
      CelebornShuffleReader.synchronized {
        if (streamCreatorPool == null) {
          streamCreatorPool = ThreadUtils.newDaemonCachedThreadPool(
            "celeborn-create-stream-thread",
            conf.readStreamCreatorPoolThreads,
            60)
        }
      }
    }

    // temp final aim: get locationStreamHandlerMap: ConcurrentHashMap[PartitionLocation, PbStreamHandler]
    // first step: update ReduceFileGroups and get relevant partitionLocation for shuffleId and partitionId.
    // to build workerRequestMap

    val startTime = System.currentTimeMillis()
    val fetchTimeoutMs = conf.clientFetchTimeoutMs
    val localFetchEnabled = conf.enableReadLocalShuffleFile
    val localHostAddress = Utils.localHostName(conf)
    val shuffleKey = Utils.makeShuffleKey(handle.appUniqueId, shuffleId)
    // startPartition is irrelevant
    val fileGroups = shuffleClient.updateFileGroup(shuffleId, startPartition)
    // host-port -> (TransportClient, PartitionLocation Array, PbOpenStreamList)
    val workerRequestMap = new util.HashMap[
      String,
      (TransportClient, util.ArrayList[PartitionLocation], PbOpenStreamList.Builder)]()

    // message PbOpenStreamList {
    //  string shuffleKey = 1;
    //  repeated string fileName = 2;
    //  repeated int32 startIndex = 3;
    //  repeated int32 endIndex = 4;
    //  repeated int32 initialCredit = 5;
    //  repeated bool readLocalShuffle = 6;
    // }

    var partCnt = 0

    (startPartition until endPartition).foreach { partitionId =>
      if (fileGroups.partitionGroups.containsKey(partitionId)) {
        fileGroups.partitionGroups.get(partitionId).asScala.foreach { location =>
          partCnt += 1
          val hostPort = location.hostAndFetchPort
          if (!workerRequestMap.containsKey(hostPort)) {
            val client = shuffleClient.getDataClientFactory().createClient(
              location.getHost,
              location.getFetchPort)
            val pbOpenStreamList = PbOpenStreamList.newBuilder()
            pbOpenStreamList.setShuffleKey(shuffleKey)
            workerRequestMap.put(
              hostPort,
              (client, new util.ArrayList[PartitionLocation], pbOpenStreamList))
          }
          val (_, locArr, pbOpenStreamListBuilder) = workerRequestMap.get(hostPort)

          locArr.add(location)
          pbOpenStreamListBuilder.addFileName(location.getFileName)
            .addStartIndex(startMapIndex)
            .addEndIndex(endMapIndex)
          pbOpenStreamListBuilder.addReadLocalShuffle(
            localFetchEnabled && location.getHost.equals(localHostAddress))
        }
      }
    }

    // second step: use workerRequestMap to build locationStreamHandlerMap
    // need to get StreamHandler from FetchHandle in CelebornWorker

    // message PbStreamHandler {
    //  int64 streamId = 1;
    //  int32 numChunks = 2;
    //  repeated int64 chunkOffsets = 3;
    //  string fullPath = 4;
    // }

    val locationStreamHandlerMap: ConcurrentHashMap[PartitionLocation, PbStreamHandler] =
      JavaUtils.newConcurrentHashMap()

    val futures = workerRequestMap.values().asScala.map { entry =>
      streamCreatorPool.submit(new Runnable {
        override def run(): Unit = {
          val (client, locArr, pbOpenStreamListBuilder) = entry
          val msg = new TransportMessage(
            MessageType.BATCH_OPEN_STREAM,
            pbOpenStreamListBuilder.build().toByteArray)

          // message PbStreamHandler {
          //  int64 streamId = 1;
          //  int32 numChunks = 2;
          //  repeated int64 chunkOffsets = 3;
          //  string fullPath = 4;
          // }
          //
          // message PbStreamHandlerOpt {
          //  int32 status = 1;
          //  PbStreamHandler streamHandler = 2;
          //  string errorMsg = 3;
          // }
          //
          // message PbOpenStreamListResponse {
          //   repeated PbStreamHandlerOpt streamHandlerOpt = 2;
          // }

          val pbOpenStreamListResponse =
            try {
              val response = client.sendRpcSync(msg.toByteBuffer, fetchTimeoutMs)
              TransportMessage.fromByteBuffer(response).getParsedPayload[PbOpenStreamListResponse]
            } catch {
              case _: Exception => null
            }
          if (pbOpenStreamListResponse != null) {
            0 until locArr.size() foreach { idx =>
              val streamHandlerOpt = pbOpenStreamListResponse.getStreamHandlerOptList.get(idx)
              if (streamHandlerOpt.getStatus == StatusCode.SUCCESS.getValue) {
                locationStreamHandlerMap.put(locArr.get(idx), streamHandlerOpt.getStreamHandler)
              }
            }
          }
        }
      })
    }.toList
    // wait for all futures to complete
    futures.foreach(f => f.get())
    val end = System.currentTimeMillis()
    logInfo(s"BatchOpenStream for $partCnt cost ${end - startTime}ms")

    // streams. key: partitionId; value: CelebornInputStream, record during iterating and prefetching

    val streams = new ConcurrentHashMap[Integer, CelebornInputStream]()

    def createInputStream(partitionId: Int): Unit = {
      val locations =
        if (fileGroups.partitionGroups.containsKey(partitionId)) {
          new util.ArrayList(fileGroups.partitionGroups.get(partitionId))
        } else new util.ArrayList[PartitionLocation]()
      val streamHandlers =
        if (locations != null) {
          val streamHandlerArr = new util.ArrayList[PbStreamHandler](locations.size())
          locations.asScala.foreach { loc =>
            streamHandlerArr.add(locationStreamHandlerMap.get(loc))
          }
          streamHandlerArr
        } else null
      if (exceptionRef.get() == null) {
        try {
          val inputStream = shuffleClient.readPartition(
            shuffleId,
            handle.shuffleId,
            partitionId,
            context.attemptNumber(),
            startMapIndex,
            endMapIndex,
            if (throwsFetchFailure) ExceptionMakerHelper.SHUFFLE_FETCH_FAILURE_EXCEPTION_MAKER
            else null,
            locations,
            streamHandlers,
            fileGroups.mapAttempts,
            metricsCallback)
          streams.put(partitionId, inputStream)
        } catch {
          case e: IOException =>
            logError(s"Exception caught when readPartition $partitionId!", e)
            exceptionRef.compareAndSet(null, e)
          case e: Throwable =>
            logError(s"Non IOException caught when readPartition $partitionId!", e)
            exceptionRef.compareAndSet(null, new CelebornIOException(e))
        }
      }
    }

    // third step: prefetch if needed

    val inputStreamCreationWindow = conf.clientInputStreamCreationWindow
    (startPartition until Math.min(
      startPartition + inputStreamCreationWindow,
      endPartition)).foreach(partitionId => {
      streamCreatorPool.submit(new Runnable {
        override def run(): Unit = {
          createInputStream(partitionId)
        }
      })
    })

    // fourth step: iterate to create InputStream and update streams

    val recordIter = (startPartition until endPartition).iterator.map(partitionId => {
      if (handle.numMappers > 0) {
        val startFetchWait = System.nanoTime()
        var inputStream: CelebornInputStream = streams.get(partitionId)
        while (inputStream == null) {
          if (exceptionRef.get() != null) {
            exceptionRef.get() match {
              case ce @ (_: CelebornIOException | _: PartitionUnRetryAbleException) =>
                if (throwsFetchFailure &&
                  shuffleClient.reportShuffleFetchFailure(handle.shuffleId, shuffleId)) {
                  throw new FetchFailedException(
                    null,
                    shuffleId,
                    -1,
                    -1,
                    partitionId,
                    SparkUtils.FETCH_FAILURE_ERROR_MSG + handle.shuffleId + "/" + shuffleId,
                    ce)
                } else
                  throw ce
              case e => throw e
            }
          }
          logInfo("inputStream is null, sleeping...")
          Thread.sleep(50)
          inputStream = streams.get(partitionId)
        }
        metricsCallback.incReadTime(
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startFetchWait))
        // ensure inputStream is closed when task completes
        context.addTaskCompletionListener[Unit](_ => inputStream.close())

        // Advance the input creation window
        if (partitionId + inputStreamCreationWindow < endPartition) {
          streamCreatorPool.submit(new Runnable {
            override def run(): Unit = {
              createInputStream(partitionId + inputStreamCreationWindow)
            }
          })
        }

        (partitionId, inputStream)
      } else {
        (partitionId, CelebornInputStream.empty())
      }
    }).filter {
      case (_, inputStream) => inputStream != CelebornInputStream.empty()
    }.map { case (partitionId, inputStream) =>
      (partitionId, serializerInstance.deserializeStream(inputStream).asKeyValueIterator)
    }.flatMap { case (partitionId, iter) =>
      try {
        iter
//        modifyIter(iter)
      } catch {
        case e @ (_: CelebornIOException | _: PartitionUnRetryAbleException) =>
          if (throwsFetchFailure &&
            shuffleClient.reportShuffleFetchFailure(handle.shuffleId, shuffleId)) {
            throw new FetchFailedException(
              null,
              handle.shuffleId,
              -1,
              -1,
              partitionId,
              SparkUtils.FETCH_FAILURE_ERROR_MSG + handle.shuffleId + "/" + shuffleId,
              e)
          } else
            throw e
      }
    }

//    def modifyIter(iter: Iterator[(Any)]):Iterator[(Any)] = {
//        iter
//    }

    val iterWithUpdatedRecordsRead =
      recordIter.map { record =>
        metrics.incRecordsRead(1)
        record
      }

    val metricIter = CompletionIterator[(Any, Any), Iterator[(Any, Any)]](
      iterWithUpdatedRecordsRead,
      context.taskMetrics().mergeShuffleReadMetrics())

    // An interruptible iterator must be used here in order to support task cancellation
    val interruptibleIter = new InterruptibleIterator[(Any, Any)](context, metricIter)

    // Before, we combine the unsorted records, then sort.
    // When we combine the unsorted records, we us ExternalAppendOnlyMap.
    // They may spill for large data. Then when we sort, we still spill for large data.
    // After, when we sort, we can easily organize the same keys together,
    // and then we no longer have to use ExternalAppendOnlyMap to combine.
    val resultIter: Iterator[Product2[K, C]] = {
      // Sort the output if there is a sort ordering defined.
      if (dep.keyOrdering.isDefined) {
        // Create an ExternalSorter to sort the data.
        val sorter: ExternalSorter[K, _, C] =
          if (dep.aggregator.isDefined) {
            if (dep.mapSideCombine) {
              new ExternalSorter[K, C, C](
                context,
                Option(new Aggregator[K, C, C](
                  identity,
                  dep.aggregator.get.mergeCombiners,
                  dep.aggregator.get.mergeCombiners)),
                ordering = Some(dep.keyOrdering.get),
                serializer = dep.serializer)
            } else {
              new ExternalSorter[K, Nothing, C](
                context,
                dep.aggregator.asInstanceOf[Option[Aggregator[K, Nothing, C]]],
                ordering = Some(dep.keyOrdering.get),
                serializer = dep.serializer)
            }
          } else {
            new ExternalSorter[K, C, C](
              context,
              ordering = Some(dep.keyOrdering.get),
              serializer = dep.serializer)
          }
        sorter.insertAll(interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]])
        context.taskMetrics().incMemoryBytesSpilled(sorter.memoryBytesSpilled)
        context.taskMetrics().incDiskBytesSpilled(sorter.diskBytesSpilled)
        context.taskMetrics().incPeakExecutionMemory(sorter.peakMemoryUsedBytes)
        // Use completion callback to stop sorter if task was finished/cancelled.
        context.addTaskCompletionListener[Unit](_ => {
          sorter.stop()
        })
        CompletionIterator[Product2[K, C], Iterator[Product2[K, C]]](sorter.iterator, sorter.stop())
      } else if (dep.aggregator.isDefined) {
        if (dep.mapSideCombine) {
          // We are reading values that are already combined
          val combinedKeyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, C)]]
          dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
        } else {
          // We don't know the value type, but also don't care -- the dependency *should*
          // have made sure its compatible w/ this aggregator, which will convert the value
          // type to the combined type C
          val keyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]]
          dep.aggregator.get.combineValuesByKey(keyValuesIterator, context)
        }
      } else {
        interruptibleIter.asInstanceOf[Iterator[(K, C)]]
      }
    }

    // agg first and then sort
    val aggregatedIter: Iterator[Product2[K, C]] =
      if (dep.aggregator.isDefined) {
        if (dep.mapSideCombine) {
          // We are reading values that are already combined
          val combinedKeyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, C)]]
          dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
        } else {
          // We don't know the value type, but also don't care -- the dependency *should*
          // have made sure its compatible w/ this aggregator, which will convert the value
          // type to the combined type C
          val keyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]]
          dep.aggregator.get.combineValuesByKey(keyValuesIterator, context)
        }
      } else {
        interruptibleIter.asInstanceOf[Iterator[Product2[K, C]]]
      }

    // Sort the output if there is a sort ordering defined.
    val resultIter1 = dep.keyOrdering match {
      case Some(keyOrd: Ordering[K]) =>
        // Create an ExternalSorter to sort the data.
        val sorter =
          new ExternalSorter[K, C, C](context, ordering = Some(keyOrd), serializer = dep.serializer)
        sorter.insertAll(aggregatedIter)
        context.taskMetrics().incMemoryBytesSpilled(sorter.memoryBytesSpilled)
        context.taskMetrics().incDiskBytesSpilled(sorter.diskBytesSpilled)
        context.taskMetrics().incPeakExecutionMemory(sorter.peakMemoryUsedBytes)
        // Use completion callback to stop sorter if task was finished/cancelled.
        context.addTaskCompletionListener[Unit](_ => {
          sorter.stop()
        })
        CompletionIterator[Product2[K, C], Iterator[Product2[K, C]]](sorter.iterator, sorter.stop())
      case None =>
        aggregatedIter
    }

    resultIter match {
      case _: InterruptibleIterator[Product2[K, C]] => resultIter
      case _ =>
        // Use another interruptible iterator here to support task cancellation as aggregator
        // or(and) sorter may have consumed previous interruptible iterator.
        new InterruptibleIterator[Product2[K, C]](context, resultIter)
    }
  }

  def newSerializerInstance(dep: ShuffleDependency[K, _, C]): SerializerInstance = {
    dep.serializer.newInstance()
  }

}

object CelebornShuffleReader {
  var streamCreatorPool: ThreadPoolExecutor = null
}
