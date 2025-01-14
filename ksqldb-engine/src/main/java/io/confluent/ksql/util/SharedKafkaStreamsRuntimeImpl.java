/*
 * Copyright 2021 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.util;

import com.google.common.base.Throwables;
import io.confluent.ksql.query.KafkaStreamsBuilder;
import io.confluent.ksql.query.QueryError;
import io.confluent.ksql.query.QueryErrorClassifier;
import io.confluent.ksql.query.QueryId;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedKafkaStreamsRuntimeImpl extends SharedKafkaStreamsRuntime {

  private final Logger log = LoggerFactory.getLogger(SharedKafkaStreamsRuntimeImpl.class);

  private QueryErrorClassifier errorClassifier;

  public SharedKafkaStreamsRuntimeImpl(final KafkaStreamsBuilder kafkaStreamsBuilder,
                                       final int maxQueryErrorsQueueSize,
                                       final Map<String, Object> streamsProperties) {
    super(
        kafkaStreamsBuilder,
        streamsProperties,
        new QueryMetadataImpl.TimeBoundedQueue(Duration.ofHours(1), maxQueryErrorsQueueSize)
    );
    kafkaStreams.start();
  }

  @Override
  public void register(
      final QueryErrorClassifier errorClassifier,
      final BinPackedPersistentQueryMetadataImpl binpackedPersistentQueryMetadata,
      final QueryId queryId
  ) {
    if (!sources.containsKey(queryId)) {
      if (sources
          .values()
          .stream()
          .flatMap(Collection::stream)
          .anyMatch(t -> binpackedPersistentQueryMetadata.getSourceNames().contains(t))) {
        throw new IllegalArgumentException(
            queryId.toString() + ": was not reserved on this runtime");
      } else {
        sources.put(queryId, binpackedPersistentQueryMetadata.getSourceNames());
      }
    }
    this.errorClassifier = errorClassifier;
    collocatedQueries.put(queryId, binpackedPersistentQueryMetadata);
    log.info("mapping {}", collocatedQueries);
  }

  @Override
  public StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse uncaughtHandler(
      final Throwable e
  ) {
    QueryError.Type errorType = QueryError.Type.UNKNOWN;
    try {
      errorType = errorClassifier.classify(e);
    } catch (final Exception classificationException) {
      log.error("Error classifying unhandled exception", classificationException);
    } finally {
      // If error classification throws then we consider the error to be an UNKNOWN error.
      // We notify listeners and add the error to the errors queue in the finally block to ensure
      // all listeners and consumers of the error queue (e.g. the API) can see it. Similarly,
      // log in finally block to make sure that if there's ever an error in the classification
      // we still get this in our logs.
      final QueryError queryError =
          new QueryError(
              System.currentTimeMillis(),
              Throwables.getStackTraceAsString(e),
              errorType
          );
      for (BinPackedPersistentQueryMetadataImpl query: collocatedQueries.values()) {
        query.getListener().onError(collocatedQueries.get(query.getQueryId()), queryError);
      }
      runtimeErrors.add(queryError);
      log.error(
          "Unhandled exception caught in streams thread {}. ({})",
          Thread.currentThread().getName(),
          errorType,
          e
      );
    }
    return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
  }

  @Override
  public void stop(final QueryId queryId) {
    log.info("Attempting to stop Query: " + queryId.toString());
    if (collocatedQueries.containsKey(queryId) && sources.containsKey(queryId)) {
      if (kafkaStreams.state().isRunningOrRebalancing()) {
        kafkaStreams.removeNamedTopology(queryId.toString());
      } else {
        throw new IllegalStateException("Streams in not running but is in state"
            + kafkaStreams.state());
      }
      //kafkaStreams.cleanUpNamedTopology(queryId.toString());
      // Once remove is blocking this can be uncommented for now it breaks
    }
  }

  @Override
  public synchronized void close() {
    kafkaStreams.close();
    kafkaStreams.cleanUp();
  }

  @Override
  public void start(final QueryId queryId) {
    if (collocatedQueries.containsKey(queryId) && !collocatedQueries.get(queryId).everStarted) {
      if (!kafkaStreams.getTopologyByName(queryId.toString()).isPresent()) {
        kafkaStreams.addNamedTopology(collocatedQueries.get(queryId).getTopology());
      } else {
        throw new IllegalArgumentException("not done removing query: " + queryId);
      }
    } else {
      throw new IllegalArgumentException("query: " + queryId + " not added to runtime");
    }
  }
}