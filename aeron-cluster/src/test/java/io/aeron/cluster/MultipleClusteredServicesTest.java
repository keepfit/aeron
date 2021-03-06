/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class MultipleClusteredServicesTest
{
    final AtomicInteger serviceAMessageCount = new AtomicInteger(0);
    final AtomicInteger serviceBMessageCount = new AtomicInteger(0);

    final class ServiceA extends TestNode.TestService
    {
        public void onSessionMessage(
            final ClientSession session,
            final long timestamp,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            serviceAMessageCount.incrementAndGet();
        }
    }

    final class ServiceB extends TestNode.TestService
    {
        public void onSessionMessage(
            final ClientSession session,
            final long timestamp,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            serviceBMessageCount.incrementAndGet();
        }
    }

    @Test
    public void testMultiService()
    {
        assertTimeoutPreemptively(ofSeconds(20), () ->
        {
            final List<TestCluster.NodeContext> nodeContexts = new ArrayList<>();
            final List<TestCluster.ServiceContext> serviceContexts = new ArrayList<>();
            final List<ClusteredMediaDriver> clusteredMediaDrivers = new ArrayList<>();
            final List<ClusteredServiceContainer> clusteredServiceContainers = new ArrayList<>();

            nodeContexts.add(TestCluster.nodeContext(0, true));
            nodeContexts.add(TestCluster.nodeContext(1, true));
            nodeContexts.add(TestCluster.nodeContext(2, true));

            serviceContexts.add(TestCluster.serviceContext(0, 0, nodeContexts.get(0), ServiceA::new));
            serviceContexts.add(TestCluster.serviceContext(0, 1, nodeContexts.get(0), ServiceB::new));
            serviceContexts.add(TestCluster.serviceContext(1, 0, nodeContexts.get(1), ServiceA::new));
            serviceContexts.add(TestCluster.serviceContext(1, 1, nodeContexts.get(1), ServiceB::new));
            serviceContexts.add(TestCluster.serviceContext(2, 0, nodeContexts.get(2), ServiceA::new));
            serviceContexts.add(TestCluster.serviceContext(2, 1, nodeContexts.get(2), ServiceB::new));

            nodeContexts.forEach((context) -> clusteredMediaDrivers.add(ClusteredMediaDriver.launch(
                context.mediaDriverContext, context.archiveContext, context.consensusModuleContext)));

            serviceContexts.forEach(
                (context) ->
                {
                    final Aeron aeron = Aeron.connect(context.aeron);
                    context.aeronArchiveContext.aeron(aeron).ownsAeronClient(false);
                    context.serviceContainerContext.aeron(aeron).ownsAeronClient(true);
                    clusteredServiceContainers.add(ClusteredServiceContainer.launch(context.serviceContainerContext));
                });

            final String aeronDirName = CommonContext.getAeronDirectoryName();

            final MediaDriver clientMediaDriver = MediaDriver.launch(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(false)
                .aeronDirectoryName(aeronDirName));

            final AeronCluster client = AeronCluster.connect(new AeronCluster.Context()
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints(TestCluster.clientMemberEndpoints(3)));

            try
            {
                final DirectBuffer buffer = new ExpandableArrayBuffer(100);

                while (client.offer(buffer, 0, 100) < 0)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                // Comment out the while loop to see more failures.
                while (serviceAMessageCount.get() < 3)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                while (serviceBMessageCount.get() < 3)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }
            }
            finally
            {
                CloseHelper.closeAll(client, clientMediaDriver);
                clusteredServiceContainers.forEach(CloseHelper::close);
                clusteredMediaDrivers.forEach(CloseHelper::close);

                clientMediaDriver.context().deleteDirectory();
                clusteredMediaDrivers.forEach((driver) -> driver.mediaDriver().context().deleteDirectory());
            }
        });
    }
}
