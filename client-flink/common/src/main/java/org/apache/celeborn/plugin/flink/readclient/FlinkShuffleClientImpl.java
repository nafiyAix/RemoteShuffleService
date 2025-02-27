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

package org.apache.celeborn.plugin.flink.readclient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import scala.reflect.ClassTag$;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Uninterruptibles;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.celeborn.client.ShuffleClientImpl;
import org.apache.celeborn.common.CelebornConf;
import org.apache.celeborn.common.exception.CelebornIOException;
import org.apache.celeborn.common.identity.UserIdentifier;
import org.apache.celeborn.common.network.TransportContext;
import org.apache.celeborn.common.network.buffer.NettyManagedBuffer;
import org.apache.celeborn.common.network.client.RpcResponseCallback;
import org.apache.celeborn.common.network.client.TransportClient;
import org.apache.celeborn.common.network.client.TransportClientFactory;
import org.apache.celeborn.common.network.protocol.PushData;
import org.apache.celeborn.common.network.protocol.PushDataHandShake;
import org.apache.celeborn.common.network.protocol.RegionFinish;
import org.apache.celeborn.common.network.protocol.RegionStart;
import org.apache.celeborn.common.network.util.TransportConf;
import org.apache.celeborn.common.protocol.PartitionLocation;
import org.apache.celeborn.common.protocol.PbChangeLocationResponse;
import org.apache.celeborn.common.protocol.TransportModuleConstants;
import org.apache.celeborn.common.protocol.message.ControlMessages;
import org.apache.celeborn.common.protocol.message.StatusCode;
import org.apache.celeborn.common.util.JavaUtils;
import org.apache.celeborn.common.util.PbSerDeUtils;
import org.apache.celeborn.common.util.Utils;
import org.apache.celeborn.common.write.PushState;
import org.apache.celeborn.plugin.flink.network.FlinkTransportClientFactory;
import org.apache.celeborn.plugin.flink.network.ReadClientHandler;

public class FlinkShuffleClientImpl extends ShuffleClientImpl {
  public static final Logger logger = LoggerFactory.getLogger(FlinkShuffleClientImpl.class);
  private static volatile FlinkShuffleClientImpl _instance;
  private static volatile boolean initialized = false;
  private FlinkTransportClientFactory flinkTransportClientFactory;
  private ReadClientHandler readClientHandler = new ReadClientHandler();
  private ConcurrentHashMap<String, TransportClient> currentClient =
      JavaUtils.newConcurrentHashMap();

  public static FlinkShuffleClientImpl get(
      String driverHost, int port, CelebornConf conf, UserIdentifier userIdentifier) {
    if (null == _instance || !initialized) {
      synchronized (FlinkShuffleClientImpl.class) {
        if (null == _instance) {
          _instance = new FlinkShuffleClientImpl(driverHost, port, conf, userIdentifier);
          _instance.setupMetaServiceRef(driverHost, port);
          initialized = true;
        } else if (!initialized) {
          _instance.shutdown();
          _instance = new FlinkShuffleClientImpl(driverHost, port, conf, userIdentifier);
          _instance.setupMetaServiceRef(driverHost, port);
          initialized = true;
        }
      }
    }
    return _instance;
  }

  @Override
  public void shutdown() {
    super.shutdown();
    if (flinkTransportClientFactory != null) {
      flinkTransportClientFactory.close();
    }
    if (readClientHandler != null) {
      readClientHandler.close();
    }
  }

  public FlinkShuffleClientImpl(
      String driverHost, int port, CelebornConf conf, UserIdentifier userIdentifier) {
    super(conf, userIdentifier);
    String module = TransportModuleConstants.DATA_MODULE;
    TransportConf dataTransportConf =
        Utils.fromCelebornConf(conf, module, conf.getInt("celeborn." + module + ".io.threads", 8));
    TransportContext context =
        new TransportContext(
            dataTransportConf, readClientHandler, conf.clientCloseIdleConnections());
    this.flinkTransportClientFactory =
        new FlinkTransportClientFactory(context, conf.fetchMaxRetriesForEachReplica());
  }

  public RssBufferStream readBufferedPartition(
      String applicationId,
      int shuffleId,
      int partitionId,
      int subPartitionIndexStart,
      int subPartitionIndexEnd)
      throws IOException {
    String shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId);
    ReduceFileGroups fileGroups = loadFileGroup(applicationId, shuffleKey, shuffleId, partitionId);
    if (fileGroups.partitionGroups.size() == 0
        || !fileGroups.partitionGroups.containsKey(partitionId)) {
      logger.warn("Shuffle data is empty for shuffle {} partitionId {}.", shuffleId, partitionId);
      return RssBufferStream.empty();
    } else {
      return RssBufferStream.create(
          this,
          conf,
          flinkTransportClientFactory,
          shuffleKey,
          fileGroups.partitionGroups.get(partitionId).toArray(new PartitionLocation[0]),
          subPartitionIndexStart,
          subPartitionIndexEnd);
    }
  }

  @Override
  protected ReduceFileGroups updateFileGroup(
      String applicationId, String shuffleKey, int shuffleId, int partitionId) throws IOException {
    ReduceFileGroups reduceFileGroups =
        reduceFileGroupsMap.computeIfAbsent(shuffleId, (id) -> new ReduceFileGroups());
    if (reduceFileGroups.partitionIds != null
        && reduceFileGroups.partitionIds.contains(partitionId)) {
      logger.debug(
          "use cached file groups for partition: {}",
          Utils.makeReducerKey(applicationId, shuffleId, partitionId));
    } else {
      synchronized (reduceFileGroups) {
        if (reduceFileGroups.partitionIds != null
            && reduceFileGroups.partitionIds.contains(partitionId)) {
          logger.debug(
              "use cached file groups for partition: {}",
              Utils.makeReducerKey(applicationId, shuffleId, partitionId));
        } else {
          // refresh file groups
          ReduceFileGroups newGroups = loadFileGroupInternal(applicationId, shuffleKey, shuffleId);
          if (newGroups == null || !newGroups.partitionIds.contains(partitionId)) {
            throw new IOException(
                "shuffle data lost for partition: "
                    + Utils.makeReducerKey(applicationId, shuffleId, partitionId));
          }
          reduceFileGroups.update(newGroups);
        }
      }
    }
    return reduceFileGroups;
  }

  public ReadClientHandler getReadClientHandler() {
    return readClientHandler;
  }

  public int pushDataToLocation(
      String applicationId,
      int shuffleId,
      int mapId,
      int attemptId,
      int partitionId,
      ByteBuf data,
      PartitionLocation location,
      Runnable closeCallBack)
      throws IOException {
    // mapKey
    final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
    final String shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId);
    // return if shuffle stage already ended
    if (mapperEnded(shuffleId, mapId, attemptId)) {
      logger.info(
          "Push data byteBuf to location {} ignored because mapper already ended for shuffle {} map {} attempt {}.",
          location.hostAndPushPort(),
          shuffleId,
          mapId,
          attemptId);
      PushState pushState = pushStates.get(mapKey);
      if (pushState != null) {
        pushState.cleanup();
      }
      return 0;
    }

    PushState pushState = getPushState(mapKey);

    // increment batchId
    final int nextBatchId = pushState.nextBatchId();
    int totalLength = data.readableBytes();
    data.markWriterIndex();
    data.writerIndex(0);
    data.writeInt(partitionId);
    data.writeInt(attemptId);
    data.writeInt(nextBatchId);
    data.writeInt(totalLength - BATCH_HEADER_SIZE);
    data.resetWriterIndex();
    logger.debug(
        "Do push data byteBuf size {} for app {} shuffle {} map {} attempt {} reduce {} batch {}.",
        totalLength,
        applicationId,
        shuffleId,
        mapId,
        attemptId,
        partitionId,
        nextBatchId);
    // check limit
    limitMaxInFlight(mapKey, pushState, location.hostAndPushPort());

    // add inFlight requests
    pushState.addBatch(nextBatchId, location.hostAndPushPort());

    // build PushData request
    NettyManagedBuffer buffer = new NettyManagedBuffer(data);
    PushData pushData = new PushData(MASTER_MODE, shuffleKey, location.getUniqueId(), buffer);

    // build callback
    RpcResponseCallback callback =
        new RpcResponseCallback() {
          @Override
          public void onSuccess(ByteBuffer response) {
            pushState.removeBatch(nextBatchId, location.hostAndPushPort());
            if (response.remaining() > 0) {
              byte reason = response.get();
              if (reason == StatusCode.STAGE_ENDED.getValue()) {
                mapperEndMap
                    .computeIfAbsent(shuffleId, (id) -> ConcurrentHashMap.newKeySet())
                    .add(mapKey);
              }
            }
            logger.debug(
                "Push data byteBuf to {} success for shuffle {} map {} attemptId {} batch {}.",
                location.hostAndPushPort(),
                shuffleId,
                mapId,
                attemptId,
                nextBatchId);
          }

          @Override
          public void onFailure(Throwable e) {
            pushState.removeBatch(nextBatchId, location.hostAndPushPort());
            if (pushState.exception.get() != null) {
              return;
            }
            if (!mapperEnded(shuffleId, mapId, attemptId)) {
              String errorMsg =
                  String.format(
                      "Push data byteBuf to %s failed for shuffle %d map %d attempt %d batch %d.",
                      location.hostAndPushPort(), shuffleId, mapId, attemptId, nextBatchId);
              pushState.exception.compareAndSet(null, new CelebornIOException(errorMsg, e));
            } else {
              logger.warn(
                  "Push data to {} failed but mapper already ended for shuffle {} map {} attempt {} batch {}.",
                  location.hostAndPushPort(),
                  shuffleId,
                  mapId,
                  attemptId,
                  nextBatchId);
            }
          }
        };
    // do push data
    try {
      TransportClient client = createClientWaitingInFlightRequest(location, mapKey, pushState);
      client.pushData(pushData, pushDataTimeout, callback, closeCallBack);
    } catch (Exception e) {
      logger.error(
          "Exception raised while pushing data byteBuf for shuffle {} map {} attempt {} partitionId {} batch {} location {}.",
          shuffleId,
          mapId,
          attemptId,
          partitionId,
          nextBatchId,
          location,
          e);
      callback.onFailure(
          new CelebornIOException(StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_MASTER, e));
    }
    return totalLength;
  }

  private TransportClient createClientWaitingInFlightRequest(
      PartitionLocation location, String mapKey, PushState pushState)
      throws IOException, InterruptedException {
    TransportClient client =
        dataClientFactory.createClient(
            location.getHost(), location.getPushPort(), location.getId());
    if (currentClient.get(mapKey) != client) {
      // make sure that messages have been sent by old client, in order to keep receiving data
      // orderly
      if (currentClient.get(mapKey) != null) {
        limitZeroInFlight(mapKey, pushState);
      }
      currentClient.put(mapKey, client);
    }
    return currentClient.get(mapKey);
  }

  public void pushDataHandShake(
      String applicationId,
      int shuffleId,
      int mapId,
      int attemptId,
      int numPartitions,
      int bufferSize,
      PartitionLocation location)
      throws IOException {
    final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
    final PushState pushState = pushStates.computeIfAbsent(mapKey, (s) -> new PushState(conf));
    sendMessageInternal(
        shuffleId,
        mapId,
        attemptId,
        location,
        pushState,
        () -> {
          String shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId);
          logger.info(
              "PushDataHandShake shuffleKey {} attemptId {} locationId {}",
              shuffleKey,
              attemptId,
              location.getUniqueId());
          logger.debug("PushDataHandShake location {}", location.toString());
          TransportClient client = createClientWaitingInFlightRequest(location, mapKey, pushState);
          PushDataHandShake handShake =
              new PushDataHandShake(
                  MASTER_MODE,
                  shuffleKey,
                  location.getUniqueId(),
                  attemptId,
                  numPartitions,
                  bufferSize);
          client.sendRpcSync(handShake.toByteBuffer(), conf.pushDataTimeoutMs());
          return null;
        });
  }

  public Optional<PartitionLocation> regionStart(
      String applicationId,
      int shuffleId,
      int mapId,
      int attemptId,
      PartitionLocation location,
      int currentRegionIdx,
      boolean isBroadcast)
      throws IOException {
    final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
    final PushState pushState = pushStates.computeIfAbsent(mapKey, (s) -> new PushState(conf));
    return sendMessageInternal(
        shuffleId,
        mapId,
        attemptId,
        location,
        pushState,
        () -> {
          String shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId);
          logger.info(
              "RegionStart for shuffle {} regionId {} attemptId {} locationId {}.",
              shuffleId,
              currentRegionIdx,
              attemptId,
              location.getUniqueId());
          logger.debug("RegionStart  for location {}.", location.toString());
          TransportClient client = createClientWaitingInFlightRequest(location, mapKey, pushState);
          RegionStart regionStart =
              new RegionStart(
                  MASTER_MODE,
                  shuffleKey,
                  location.getUniqueId(),
                  attemptId,
                  currentRegionIdx,
                  isBroadcast);
          ByteBuffer regionStartResponse =
              client.sendRpcSync(regionStart.toByteBuffer(), conf.pushDataTimeoutMs());
          if (regionStartResponse.hasRemaining()
              && regionStartResponse.get() == StatusCode.HARD_SPLIT.getValue()) {
            // if split then revive
            PbChangeLocationResponse response =
                driverRssMetaService.askSync(
                    ControlMessages.Revive$.MODULE$.apply(
                        applicationId,
                        shuffleId,
                        mapId,
                        attemptId,
                        location.getId(),
                        location.getEpoch(),
                        location,
                        StatusCode.HARD_SPLIT),
                    conf.requestPartitionLocationRpcAskTimeout(),
                    ClassTag$.MODULE$.apply(PbChangeLocationResponse.class));
            // per partitionKey only serve single PartitionLocation in Client Cache.
            StatusCode respStatus = Utils.toStatusCode(response.getStatus());
            if (StatusCode.SUCCESS.equals(respStatus)) {
              return Optional.of(PbSerDeUtils.fromPbPartitionLocation(response.getLocation()));
            } else if (StatusCode.MAP_ENDED.equals(respStatus)) {
              mapperEndMap
                  .computeIfAbsent(shuffleId, (id) -> ConcurrentHashMap.newKeySet())
                  .add(mapKey);
              return Optional.empty();
            } else {
              // throw exception
              logger.error(
                  "Exception raised while reviving for shuffle {} map {} attemptId {} partition {} epoch {}.",
                  shuffleId,
                  mapId,
                  attemptId,
                  location.getId(),
                  location.getEpoch());
              throw new CelebornIOException("RegionStart revive failed");
            }
          }
          return Optional.empty();
        });
  }

  public void regionFinish(
      String applicationId, int shuffleId, int mapId, int attemptId, PartitionLocation location)
      throws IOException {
    final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
    final PushState pushState = pushStates.computeIfAbsent(mapKey, (s) -> new PushState(conf));
    sendMessageInternal(
        shuffleId,
        mapId,
        attemptId,
        location,
        pushState,
        () -> {
          final String shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId);
          logger.info(
              "RegionFinish for shuffle {} map {} attemptId {} locationId {}.",
              shuffleId,
              mapId,
              attemptId,
              location.getUniqueId());
          logger.debug("RegionFinish for location {}.", location.toString());
          TransportClient client = createClientWaitingInFlightRequest(location, mapKey, pushState);
          RegionFinish regionFinish =
              new RegionFinish(MASTER_MODE, shuffleKey, location.getUniqueId(), attemptId);
          client.sendRpcSync(regionFinish.toByteBuffer(), conf.pushDataTimeoutMs());
          return null;
        });
  }

  private <R> R sendMessageInternal(
      int shuffleId,
      int mapId,
      int attemptId,
      PartitionLocation location,
      PushState pushState,
      ThrowingExceptionSupplier<R, Exception> supplier)
      throws IOException {
    int batchId = 0;
    try {
      // mapKey
      final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
      // return if shuffle stage already ended
      if (mapperEnded(shuffleId, mapId, attemptId)) {
        logger.debug(
            "Send message to {} ignored because mapper already ended for shuffle {} map {} attempt {}.",
            location.hostAndPushPort(),
            shuffleId,
            mapId,
            attemptId);
        return null;
      }
      pushState = getPushState(mapKey);

      // add inFlight requests
      batchId = pushState.nextBatchId();
      pushState.addBatch(batchId, location.hostAndPushPort());
      return retrySendMessage(supplier);
    } finally {
      if (pushState != null) {
        pushState.removeBatch(batchId, location.hostAndPushPort());
      }
    }
  }

  @FunctionalInterface
  interface ThrowingExceptionSupplier<R, E extends Exception> {
    R get() throws E;
  }

  private <R> R retrySendMessage(ThrowingExceptionSupplier<R, Exception> supplier)
      throws IOException {

    int retryTimes = 0;
    boolean isSuccess = false;
    Exception currentException = null;
    R result = null;
    while (!Thread.currentThread().isInterrupted()
        && !isSuccess
        && retryTimes < conf.networkIoMaxRetries(TransportModuleConstants.PUSH_MODULE)) {
      logger.debug("RetrySendMessage  retry times {}.", retryTimes);
      try {
        result = supplier.get();
        isSuccess = true;
      } catch (Exception e) {
        currentException = e;
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        if (shouldRetry(e)) {
          retryTimes++;
          Uninterruptibles.sleepUninterruptibly(
              conf.networkIoRetryWaitMs(TransportModuleConstants.PUSH_MODULE),
              TimeUnit.MILLISECONDS);
        } else {
          break;
        }
      }
    }
    if (!isSuccess) {
      if (currentException instanceof IOException) {
        throw (IOException) currentException;
      } else {
        throw new CelebornIOException(currentException.getMessage(), currentException);
      }
    }
    return result;
  }

  private boolean shouldRetry(Throwable e) {
    boolean isIOException =
        e instanceof IOException
            || e instanceof TimeoutException
            || (e.getCause() != null && e.getCause() instanceof TimeoutException)
            || (e.getCause() != null && e.getCause() instanceof IOException)
            || (e instanceof RuntimeException
                && e.getMessage() != null
                && e.getMessage().startsWith(IOException.class.getName()));
    return isIOException;
  }

  @Override
  public void cleanup(String applicationId, int shuffleId, int mapId, int attemptId) {
    final String mapKey = Utils.makeMapKey(shuffleId, mapId, attemptId);
    super.cleanup(applicationId, shuffleId, mapId, attemptId);
    if (currentClient != null) {
      currentClient.remove(mapKey);
    }
  }

  public void setDataClientFactory(TransportClientFactory dataClientFactory) {
    this.dataClientFactory = dataClientFactory;
  }

  @VisibleForTesting
  public TransportClientFactory getDataClientFactory() {
    return flinkTransportClientFactory;
  }
}
