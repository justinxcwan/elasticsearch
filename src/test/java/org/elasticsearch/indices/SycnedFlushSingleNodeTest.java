/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.indices;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ElasticsearchSingleNodeTest;
import org.elasticsearch.test.SyncedFlushUtil;
import org.elasticsearch.test.hamcrest.ElasticsearchAssertions;

import java.util.List;
import java.util.Map;

/**
 */
public class SycnedFlushSingleNodeTest extends ElasticsearchSingleNodeTest {

    public void testModificationPreventsSealing() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "test", "1").setSource("{}").get();
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        final ShardId shardId = shard.shardId();
        final ClusterState state = getInstanceFromNode(ClusterService.class).state();
        final IndexShardRoutingTable shardRoutingTable = flushService.getActiveShardRoutings(shardId, state);
        final List<ShardRouting> activeShards = shardRoutingTable.activeShards();
        assertEquals("exactly one active shard", 1, activeShards.size());
        Map<String, byte[]> commitIds = flushService.sendPreSyncRequests(activeShards, state, shardId);
        assertEquals("exactly one commit id", 1, commitIds.size());
        client().prepareIndex("test", "test", "2").setSource("{}").get();
        String syncId = Strings.base64UUID();
        SyncedFlushUtil.SyncResultListener listener = new SyncedFlushUtil.SyncResultListener();
        flushService.sendSyncRequests(syncId, activeShards, state, commitIds, shardId,listener);
        listener.latch.await();
        assertNull(listener.error);
        SyncedFlushService.SyncedFlushResult syncedFlushResult = listener.result;
        assertNotNull(syncedFlushResult);
        assertEquals(0, syncedFlushResult.successfulShards());
        assertEquals(1, syncedFlushResult.totalShards());
        assertEquals(syncId, syncedFlushResult.syncId());
        assertNotNull(syncedFlushResult.shardResponses().get(activeShards.get(0)));
        assertFalse(syncedFlushResult.shardResponses().get(activeShards.get(0)).success());
        assertEquals("pending operations", syncedFlushResult.shardResponses().get(activeShards.get(0)).failureReason());

        flushService.sendPreSyncRequests(activeShards, state, shardId); // pull another commit and make sure we can't seal with the old one
        listener = new SyncedFlushUtil.SyncResultListener();
        flushService.sendSyncRequests(syncId, activeShards, state, commitIds, shardId,listener);
        listener.latch.await();
        assertNull(listener.error);
        syncedFlushResult = listener.result;
        assertNotNull(syncedFlushResult);
        assertEquals(0, syncedFlushResult.successfulShards());
        assertEquals(1, syncedFlushResult.totalShards());
        assertEquals(syncId, syncedFlushResult.syncId());
        assertNotNull(syncedFlushResult.shardResponses().get(activeShards.get(0)));
        assertFalse(syncedFlushResult.shardResponses().get(activeShards.get(0)).success());
        assertEquals("commit has changed", syncedFlushResult.shardResponses().get(activeShards.get(0)).failureReason());
        ElasticsearchAssertions.assertVersionSerializable(syncedFlushResult);
    }

    public void testSingleShardSuccess() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "test", "1").setSource("{}").get();
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        final ShardId shardId = shard.shardId();
        SyncedFlushUtil.SyncResultListener listener = new SyncedFlushUtil.SyncResultListener();
        flushService.attemptSyncedFlush(shardId, listener);
        listener.latch.await();
        assertNull(listener.error);
        SyncedFlushService.SyncedFlushResult syncedFlushResult = listener.result;
        assertNotNull(syncedFlushResult);
        assertEquals(1, syncedFlushResult.successfulShards());
        assertEquals(1, syncedFlushResult.totalShards());
        SyncedFlushService.SyncedFlushResponse response = syncedFlushResult.shardResponses().values().iterator().next();
        assertTrue(response.success());
        ElasticsearchAssertions.assertVersionSerializable(syncedFlushResult);
    }

    public void testSyncFailsIfOperationIsInFlight() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "test", "1").setSource("{}").get();
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        final ShardId shardId = shard.shardId();
        shard.incrementOperationCounter();
        try {
            SyncedFlushUtil.SyncResultListener listener = new SyncedFlushUtil.SyncResultListener();
            flushService.attemptSyncedFlush(shardId, listener);
            listener.latch.await();
            assertNull(listener.error);
            SyncedFlushService.SyncedFlushResult syncedFlushResult = listener.result;
            assertNotNull(syncedFlushResult);
            assertEquals(0, syncedFlushResult.successfulShards());
            assertEquals(0, syncedFlushResult.totalShards());
            assertEquals("operation counter on primary is non zero [2]", syncedFlushResult.failureReason());
            ElasticsearchAssertions.assertVersionSerializable(syncedFlushResult);
        } finally {
            shard.decrementOperationCounter();
        }
    }

    public void testSyncFailsOnIndexClosedOrMissing() throws InterruptedException {
        createIndex("test");
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        SyncedFlushUtil.SyncResultListener  listener = new SyncedFlushUtil.SyncResultListener();
        flushService.attemptSyncedFlush(new ShardId("test", 1), listener);
        listener.latch.await();
        assertNotNull(listener.error);
        assertNull(listener.result);
        assertEquals("missing", listener.error.getMessage());

        final ShardId shardId = shard.shardId();

        client().admin().indices().prepareClose("test").get();
        listener = new SyncedFlushUtil.SyncResultListener();
        flushService.attemptSyncedFlush(shardId, listener);
        listener.latch.await();
        assertNotNull(listener.error);
        assertNull(listener.result);
        assertEquals("closed", listener.error.getMessage());

        listener = new SyncedFlushUtil.SyncResultListener();
        flushService.attemptSyncedFlush(new ShardId("nosuchindex", 0), listener);
        listener.latch.await();
        assertNotNull(listener.error);
        assertNull(listener.result);
        assertEquals("no such index", listener.error.getMessage());
    }
    
    public void testFailAfterIntermediateCommit() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "test", "1").setSource("{}").get();
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        final ShardId shardId = shard.shardId();
        final ClusterState state = getInstanceFromNode(ClusterService.class).state();
        final IndexShardRoutingTable shardRoutingTable = flushService.getActiveShardRoutings(shardId, state);
        final List<ShardRouting> activeShards = shardRoutingTable.activeShards();
        assertEquals("exactly one active shard", 1, activeShards.size());
        Map<String, byte[]> commitIds = flushService.sendPreSyncRequests(activeShards, state, shardId);
        assertEquals("exactly one commit id", 1, commitIds.size());
        if (randomBoolean()) {
            client().prepareIndex("test", "test", "2").setSource("{}").get();
        }
        client().admin().indices().prepareFlush("test").setForce(true).get();
        String syncId = Strings.base64UUID();
        final SyncedFlushUtil.SyncResultListener listener = new SyncedFlushUtil.SyncResultListener();
        flushService.sendSyncRequests(syncId, activeShards, state, commitIds, shardId, listener);
        listener.latch.await();
        assertNull(listener.error);
        SyncedFlushService.SyncedFlushResult syncedFlushResult = listener.result;
        assertNotNull(syncedFlushResult);
        assertEquals(0, syncedFlushResult.successfulShards());
        assertEquals(1, syncedFlushResult.totalShards());
        assertEquals(syncId, syncedFlushResult.syncId());
        assertNotNull(syncedFlushResult.shardResponses().get(activeShards.get(0)));
        assertFalse(syncedFlushResult.shardResponses().get(activeShards.get(0)).success());
        assertEquals("commit has changed", syncedFlushResult.shardResponses().get(activeShards.get(0)).failureReason());
        ElasticsearchAssertions.assertVersionSerializable(syncedFlushResult);
    }

    public void testFailWhenCommitIsMissing() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "test", "1").setSource("{}").get();
        IndexService test = getInstanceFromNode(IndicesService.class).indexService("test");
        IndexShard shard = test.shard(0);

        SyncedFlushService flushService = getInstanceFromNode(SyncedFlushService.class);
        final ShardId shardId = shard.shardId();
        final ClusterState state = getInstanceFromNode(ClusterService.class).state();
        final IndexShardRoutingTable shardRoutingTable = flushService.getActiveShardRoutings(shardId, state);
        final List<ShardRouting> activeShards = shardRoutingTable.activeShards();
        assertEquals("exactly one active shard", 1, activeShards.size());
        Map<String, byte[]> commitIds = flushService.sendPreSyncRequests(activeShards, state, shardId);
        assertEquals("exactly one commit id", 1, commitIds.size());
        commitIds.clear(); // wipe it...
        String syncId = Strings.base64UUID();
        SyncedFlushUtil.SyncResultListener listener = new SyncedFlushUtil.SyncResultListener();
        flushService.sendSyncRequests(syncId, activeShards, state, commitIds, shardId, listener);
        listener.latch.await();
        assertNull(listener.error);
        SyncedFlushService.SyncedFlushResult syncedFlushResult = listener.result;
        assertNotNull(syncedFlushResult);
        assertEquals(0, syncedFlushResult.successfulShards());
        assertEquals(1, syncedFlushResult.totalShards());
        assertEquals(syncId, syncedFlushResult.syncId());
        assertNotNull(syncedFlushResult.shardResponses().get(activeShards.get(0)));
        assertFalse(syncedFlushResult.shardResponses().get(activeShards.get(0)).success());
        assertEquals("no commit id from pre-sync flush", syncedFlushResult.shardResponses().get(activeShards.get(0)).failureReason());
        ElasticsearchAssertions.assertVersionSerializable(syncedFlushResult);
    }


}