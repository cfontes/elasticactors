/*
 * Copyright 2013 - 2015 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticsoftware.elasticactors.cassandra2.state;

import com.datastax.driver.core.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsoftware.elasticactors.util.concurrent.ThreadBoundEventProcessor;

import java.util.*;

import static com.datastax.driver.core.BatchStatement.Type.UNLOGGED;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static org.elasticsoftware.elasticactors.cassandra2.util.ExecutionUtils.executeWithRetry;

/**
 * @author Joost van de Wijgerd
 */
public final class PersistentActorUpdateEventProcessor implements ThreadBoundEventProcessor<PersistentActorUpdateEvent> {
    private static final Logger logger = LogManager.getLogger(PersistentActorUpdateEventProcessor.class);
    public static final String INSERT_QUERY = "INSERT INTO \"PersistentActors\" (key, key2, column1, value) VALUES (?, ?, ?, ?)";
    public static final String DELETE_QUERY = "DELETE FROM \"PersistentActors\" where key = ? AND key2 = ? AND column1 = ?";
    private final Session cassandraSession;
    private final PreparedStatement insertStatement;
    private final PreparedStatement deleteStatement;
    private final Map<Integer,PreparedStatement> batchStatements = new HashMap<>();
    private final boolean optimizedV1Batches;

    public PersistentActorUpdateEventProcessor(Session cassandraSession, int maxBatchSize) {
        this(cassandraSession, maxBatchSize, true);
    }

    public PersistentActorUpdateEventProcessor(Session cassandraSession, int maxBatchSize, boolean optimizedV1Batches) {
        this.cassandraSession = cassandraSession;
        this.insertStatement = cassandraSession.prepare(INSERT_QUERY);
        this.deleteStatement = cassandraSession.prepare(DELETE_QUERY);
        if(optimizedV1Batches) {
            prepateBatchIfNeeded(maxBatchSize);
        }
        this.optimizedV1Batches = optimizedV1Batches;
    }

    /**
     * Helper methods to optimize for the batching INSERT statements (protocol V1 only)
     *
     * @param maxBatchSize
     */
    private void prepateBatchIfNeeded(int maxBatchSize) {
        // check the protocol to see if BatchStatements are supported
        ProtocolVersion protocolVersion = cassandraSession.getCluster().getConfiguration().getProtocolOptions().getProtocolVersion();
        if(ProtocolVersion.V1.equals(protocolVersion)) {
            for (int batchSize = 2; batchSize <= maxBatchSize ; batchSize++) {
                // create a prepared statement (INSERT only)
                StringBuilder batchBuilder = new StringBuilder("BEGIN UNLOGGED BATCH ");
                for (int i = 0; i < batchSize; i++) {
                    batchBuilder.append("   ").append(INSERT_QUERY).append("; ");
                }
                batchBuilder.append("APPLY BATCH");
                batchStatements.put(batchSize, cassandraSession.prepare(batchBuilder.toString()));
            }
        }
    }

    @Override
    public void process(PersistentActorUpdateEvent... events) {
        process(Arrays.asList(events));
    }

    @Override
    public void process(List<PersistentActorUpdateEvent> events) {
        Exception executionException = null;
        final long startTime = currentTimeMillis();
        try {
            // optimized to use the prepared statement
            if(events.size() == 1) {
                PersistentActorUpdateEvent event = events.get(0);
                BoundStatement boundStatement;
                if(event.getPersistentActorBytes() != null) {
                    boundStatement = insertStatement.bind(event.getRowKey()[0], event.getRowKey()[1], event.getPersistentActorId(), event.getPersistentActorBytes());
                } else {
                    // it's a delete
                    boundStatement = deleteStatement.bind(event.getRowKey()[0], event.getRowKey()[1], event.getPersistentActorId());
                }
                // execute the statement
                executeWithRetry(cassandraSession, boundStatement, logger);
            } else {
                // check the protocol to see if BatchStatements are supported
                ProtocolVersion protocolVersion = cassandraSession.getCluster().getConfiguration().getProtocolOptions().getProtocolVersion();
                if(ProtocolVersion.V1.equals(protocolVersion)) {
                    if(this.optimizedV1Batches) {
                        executeBatchV1Optimized(events);
                    } else {
                        executeBatchV1(events);
                    }
                } else {
                    executeBatchV2AndUp(events);
                }
            }
        } catch(Exception e) {
            executionException = e;
        } finally {
            for (PersistentActorUpdateEvent event : events) {
                if(event.getEventListener() != null) {
                    if (executionException == null) {
                        event.getEventListener().onDone(event.getMessage());
                    } else {
                        event.getEventListener().onError(event.getMessage(), executionException);
                    }
                }
            }
            // add some trace info
            if(logger.isTraceEnabled()) {
                final long endTime = currentTimeMillis();
                logger.trace(format("Updating %d Actor state entrie(s) took %d msecs",events.size(),endTime-startTime));
            }
        }
    }

    private void executeBatchV1(List<PersistentActorUpdateEvent> events) {
        List<Object> arguments = new LinkedList<>();
        StringBuilder batchBuilder = new StringBuilder("BEGIN UNLOGGED BATCH ");
        for (PersistentActorUpdateEvent event : events) {
            batchBuilder.append("   ");
            if(event.getPersistentActorBytes() != null) {
                // insert query
                batchBuilder.append(INSERT_QUERY);
                // add the 4 arguments in order
                arguments.add(event.getRowKey()[0]);
                arguments.add(event.getRowKey()[1]);
                arguments.add(event.getPersistentActorId());
                arguments.add(event.getPersistentActorBytes());
            } else {
                // delete query
                batchBuilder.append(DELETE_QUERY);
                // add the 3 arguments in order
                arguments.add(event.getRowKey()[0]);
                arguments.add(event.getRowKey()[1]);
                arguments.add(event.getPersistentActorId());
            }
            batchBuilder.append("; ");
        }
        batchBuilder.append("APPLY BATCH");
        // @todo: this causes a warning, but doing it without seems to fail with binary values!
        PreparedStatement batchStatement = cassandraSession.prepare(batchBuilder.toString());
        executeWithRetry(cassandraSession, batchStatement.bind(arguments.toArray()), logger);
    }

    private void executeBatchV1Optimized(List<PersistentActorUpdateEvent> events) {
        // assume most common case is inserts
        List<Object> arguments = new LinkedList<>();
        int batchSize = 0;
        for (PersistentActorUpdateEvent event : events) {
            if(event.getPersistentActorBytes() != null) {
                // add the 4 arguments in order
                arguments.add(event.getRowKey()[0]);
                arguments.add(event.getRowKey()[1]);
                arguments.add(event.getPersistentActorId());
                arguments.add(event.getPersistentActorBytes());
                batchSize += 1;
            } else {
                // not supported, we need to fall back to the un-optimized version
                break;
            }
        }
        PreparedStatement batchStatement = null;
        if(batchSize == events.size()) {
            batchStatement = batchStatements.get(batchSize);
        }
        if(batchStatement != null) {
            executeWithRetry(cassandraSession, batchStatement.bind(arguments.toArray()), logger);
        } else {
            // fallback to non-optimized version
            executeBatchV1(events);
        }
    }

    private void executeBatchV2AndUp(List<PersistentActorUpdateEvent> events) {
        BatchStatement batchStatement = new BatchStatement(UNLOGGED);
        for (PersistentActorUpdateEvent event : events) {
            if (event.getPersistentActorBytes() != null) {
                batchStatement.add(insertStatement.bind(event.getRowKey()[0], event.getRowKey()[1], event.getPersistentActorId(), event.getPersistentActorBytes()));
            } else {
                // it's a delete
                batchStatement.add(deleteStatement.bind(event.getRowKey()[0], event.getRowKey()[1], event.getPersistentActorId()));
            }
        }
        executeWithRetry(cassandraSession, batchStatement, logger);
    }



}
