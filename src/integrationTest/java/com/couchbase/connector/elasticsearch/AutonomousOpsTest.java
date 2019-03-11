/*
 * Copyright 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connector.elasticsearch;

import com.couchbase.client.deps.io.netty.util.ResourceLeakDetector;
import com.couchbase.connector.cluster.Membership;
import com.couchbase.connector.cluster.consul.AsyncTask;
import com.couchbase.connector.cluster.consul.ConsulConnector;
import com.couchbase.connector.cluster.consul.ConsulContext;
import com.couchbase.connector.cluster.consul.DocumentKeys;
import com.couchbase.connector.cluster.consul.WorkerService;
import com.couchbase.connector.cluster.consul.rpc.Broadcaster;
import com.couchbase.connector.cluster.consul.rpc.RpcEndpoint;
import com.couchbase.connector.config.es.ConnectorConfig;
import com.couchbase.connector.testcontainers.CustomCouchbaseContainer;
import com.couchbase.connector.testcontainers.ElasticsearchContainer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.orbitz.consul.Consul;
import com.orbitz.consul.KeyValueClient;
import org.elasticsearch.Version;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.couchbase.connector.elasticsearch.TestConfigHelper.readConfig;
import static com.couchbase.connector.testcontainers.CustomCouchbaseContainer.newCouchbaseCluster;
import static com.couchbase.connector.testcontainers.Poller.poll;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AutonomousOpsTest {
  static {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  private static final String couchbaseVersion = "enterprise-6.0.1";
  private static final String elasticsearchVersion = "6.6.0";

  private static CustomCouchbaseContainer couchbase;
  private static ElasticsearchContainer elasticsearch;
  private static ConsulCluster consulCluster;

  @BeforeClass
  public static void startReusableContainers() {
    consulCluster = new ConsulCluster("consul:1.4.3", 3, Network.newNetwork()).start();

    elasticsearch = new ElasticsearchContainer(Version.fromString(elasticsearchVersion))
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.elasticsearch")));
    elasticsearch.start();

    couchbase = newCouchbaseCluster("couchbase/server:" + couchbaseVersion);
    couchbase.loadSampleBucket("travel-sample", 100);

    System.out.println("Couchbase " + couchbase.getVersionString() +
        " listening at http://" + DockerHelper.getDockerHost() + ":" + couchbase.getMappedPort(8091));
  }

  @AfterClass
  public static void stopReusableContainers() {
    couchbase.stop();
    elasticsearch.stop();
    consulCluster.stop();
  }

  @Before
  public void foo() throws Exception {
    final ConnectorConfig config = ConnectorConfig.from(readConfig(couchbase, elasticsearch));
    try (TestEsClient es = new TestEsClient(config)) {
      es.deleteAllIndexes();
    }
  }

  private static final AtomicInteger nameCounter = new AtomicInteger();

  private static String newGroupName() {
    return "integrationTest-" + nameCounter.incrementAndGet();
  }

  @Test
  public void singleWorker() throws Exception {

    final String group = newGroupName();
    final String config = readConfig(couchbase, elasticsearch, ImmutableMap.of(
        "group.name", group));

    final Consul.Builder consulBuilder = consulCluster.clientBuilder(0);
    final ConsulContext consulContext = new ConsulContext(consulBuilder, group, null);

    final KeyValueClient kv = consulContext.consul().keyValueClient();
    final DocumentKeys keys = consulContext.keys();
    kv.putValue(keys.config(), config);

    try (AsyncTask connector = AsyncTask.run(() -> ConsulConnector.run(consulContext));
         TestEsClient es = new TestEsClient(ConnectorConfig.from(config))) {
      System.out.println("connector has been started.");

      final int airlines = 187;
      final int routes = 24024;

      final int expectedAirlineCount = airlines + routes;
      final int expectedAirportCount = 1968;

      poll().until(() -> es.getDocumentCount("airlines") >= expectedAirlineCount);
      poll().until(() -> es.getDocumentCount("airports") >= expectedAirportCount);

      SECONDS.sleep(3); // quiet period, make sure no more documents appear in the index

      assertEquals(expectedAirlineCount, es.getDocumentCount("airlines"));
      assertEquals(expectedAirportCount, es.getDocumentCount("airports"));
    }
  }

  @Test
  public void threeWorkers() throws Exception {
    final String group = newGroupName();
    final String config = readConfig(couchbase, elasticsearch, ImmutableMap.of(
        "group.name", group));

    final Consul.Builder consulBuilder = consulCluster.clientBuilder(0);
    try (ConsulContext consulContext = new ConsulContext(consulBuilder, group, null)) {
      final KeyValueClient kv = consulContext.consul().keyValueClient();
      final DocumentKeys keys = consulContext.keys();
      kv.putValue(keys.config(), config);

      keys.pause();

      try (AsyncTask connector1 = AsyncTask.run(() -> ConsulConnector.run(new ConsulContext(consulCluster.clientBuilder(0), group, null)))) {
        // Wait for first instance to assume role of leader
        poll().until(() -> keys.leaderEndpoint().isPresent());

        try (Broadcaster broadcaster = new Broadcaster();
             AsyncTask connector2 = AsyncTask.run(() -> ConsulConnector.run(new ConsulContext(consulCluster.clientBuilder(1), group, null)));
             AsyncTask connector3 = AsyncTask.run(() -> ConsulConnector.run(new ConsulContext(consulCluster.clientBuilder(2), group, null)));
             TestEsClient es = new TestEsClient(ConnectorConfig.from(config))) {
          System.out.println("connector has been started.");

          // Wait for all instances to bind to RPC endpoints
          poll().until(() -> keys.listRpcEndpoints().size() == 3);

          // Membership is assigned by the leader when the leader tells everyone to start streaming.
          // Nobody should be streaming yet, since the group is paused.
          final List<RpcEndpoint> endpoints = keys.listRpcEndpoints();
          for (RpcEndpoint e : endpoints) {
            final WorkerService.Status status = e.service(WorkerService.class).status();
            assertNull(status.getMembership());
          }

          // Allow streaming to begin
          keys.resume();

          // Wait until each worker is streaming
          poll().until(() -> endpoints.stream()
              .map(ep -> ep.service(WorkerService.class).status())
              .noneMatch(status -> status.getMembership() == null));

          // Ask everyone which membership role they've been assigned.
          final Set<Membership> memberships = broadcaster.broadcast("status", endpoints, WorkerService.class, WorkerService::status)
              .values()
              .stream()
              .map(result -> result.get().getMembership())
              .collect(toSet());

          // expect 3 unique members to be streaming
          assertEquals(
              ImmutableSet.of(
                  Membership.of(1, 3),
                  Membership.of(2, 3),
                  Membership.of(3, 3)),
              memberships);

          SECONDS.sleep(2);
          System.out.println("Stopping leader");
          connector1.close();
          System.out.println("Leader stopped!");

          // Wait for new leader to take over and rebalance among remaining workers
          poll().until(() -> broadcaster.broadcast("status", keys.listRpcEndpoints(), WorkerService.class, WorkerService::status)
              .values()
              .stream()
              .map(result -> result.get().getMembership())
              .collect(toSet())
              .equals(ImmutableSet.of(Membership.of(1, 2), Membership.of(2, 2))));

          System.out.println("Cluster rebalanced!!");

          final int airlines = 187;
          final int routes = 24024;

          final int expectedAirlineCount = airlines + routes;
          final int expectedAirportCount = 1968;

          poll().until(() -> es.getDocumentCount("airlines") >= expectedAirlineCount);
          poll().until(() -> es.getDocumentCount("airports") >= expectedAirportCount);

          SECONDS.sleep(3); // quiet period, make sure no more documents appear in the index

          assertEquals(expectedAirlineCount, es.getDocumentCount("airlines"));
          assertEquals(expectedAirportCount, es.getDocumentCount("airports"));

          System.out.println("Shutting down connector...");
        }
      }
      System.out.println("Connector shutdown complete.");
    }
  }
}