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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MultiDestinationSubscriptionTest
{
    private static final String UNICAST_ENDPOINT_A = "localhost:24325";
    private static final String UNICAST_ENDPOINT_B = "localhost:24326";

    private static final String PUB_UNICAST_URI = "aeron:udp?endpoint=localhost:24325";
    private static final String PUB_MULTICAST_URI = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost";
    private static final String PUB_MDC_URI = "aeron:udp?control=localhost:24325|control-mode=dynamic";

    private static final String SUB_URI = "aeron:udp?control-mode=manual";
    private static final String SUB_MDC_DESTINATION_URI = "aeron:udp?endpoint=localhost:24326|control=localhost:24325";

    private static final int STREAM_ID = 1001;

    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int NUM_MESSAGES_PER_TERM = 64;
    private static final int MESSAGE_LENGTH =
        (TERM_BUFFER_LENGTH / NUM_MESSAGES_PER_TERM) - DataHeaderFlyweight.HEADER_LENGTH;
    private static final String ROOT_DIR =
        SystemUtil.tmpDirName() + "aeron-system-tests-" + UUID.randomUUID().toString() + File.separator;

    private final MediaDriver.Context driverContextA = new MediaDriver.Context();
    private final MediaDriver.Context driverContextB = new MediaDriver.Context();

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driverA;
    private TestMediaDriver driverB;
    private Publication publicationA;
    private Publication publicationB;
    private Subscription subscription;
    private Subscription copySubscription;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final FragmentHandler fragmentHandler = mock(FragmentHandler.class);
    private final FragmentHandler copyFragmentHandler = mock(FragmentHandler.class);

    private void launch()
    {
        TestMediaDriver.notSupportedOnCMediaDriverYet("Multi-destination-cast not available");

        final String baseDirA = ROOT_DIR + "A";

        buffer.putInt(0, 1);

        driverContextA
            .errorHandler(Throwable::printStackTrace)
            .publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirA)
            .threadingMode(ThreadingMode.SHARED);

        driverA = TestMediaDriver.launch(driverContextA);
        clientA = Aeron.connect(new Aeron.Context().aeronDirectoryName(driverContextA.aeronDirectoryName()));
    }

    private void launchSecond()
    {
        final String baseDirB = ROOT_DIR + "B";

        driverContextB
            .errorHandler(Throwable::printStackTrace)
            .publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirB)
            .threadingMode(ThreadingMode.SHARED);

        driverB = TestMediaDriver.launch(driverContextB);
        clientB = Aeron.connect(new Aeron.Context().aeronDirectoryName(driverContextB.aeronDirectoryName()));
    }

    @AfterEach
    public void closeEverything()
    {
        CloseHelper.close(publicationA);
        CloseHelper.close(publicationB);
        CloseHelper.close(subscription);
        CloseHelper.close(copySubscription);
        CloseHelper.close(clientA);
        CloseHelper.close(driverA);
        CloseHelper.close(clientB);
        CloseHelper.close(driverB);

        IoUtil.delete(new File(ROOT_DIR), true);
    }

    @Test
    public void subscriptionCloseShouldAlsoCloseMediaDriverPorts()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            launch();

            final String publicationChannelA = new ChannelUriStringBuilder()
                .media(CommonContext.UDP_MEDIA)
                .endpoint(UNICAST_ENDPOINT_A)
                .build();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(publicationChannelA);

            CloseHelper.close(subscription);
            CloseHelper.close(clientA);

            clientA = Aeron.connect(new Aeron.Context().aeronDirectoryName(driverContextA.aeronDirectoryName()));
            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);

            subscription.addDestination(publicationChannelA);
        });
    }

    @Test
    public void shouldSpinUpAndShutdownWithUnicast()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(PUB_UNICAST_URI);

            publicationA = clientA.addPublication(PUB_UNICAST_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }
        });
    }

    @Test
    public void shouldSpinUpAndShutdownWithMulticast()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            final long correlationId = subscription.asyncAddDestination(PUB_MULTICAST_URI);

            publicationA = clientA.addPublication(PUB_MULTICAST_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            assertFalse(clientA.isCommandActive(correlationId));
        });
    }

    @Test
    public void shouldSpinUpAndShutdownWithDynamicMdc()
    {
        assertTimeoutPreemptively(ofSeconds(20), () ->
        {
            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(SUB_MDC_DESTINATION_URI);

            publicationA = clientA.addPublication(PUB_MDC_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }
        });
    }

    @Test
    public void shouldSendToSingleDestinationSubscriptionWithUnicast()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;

            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(PUB_UNICAST_URI);

            publicationA = clientA.addPublication(PUB_UNICAST_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSend; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);
            }

            verifyFragments(fragmentHandler, numMessagesToSend);
        });
    }

    @Test
    public void shouldSendToSingleDestinationMultipleSubscriptionsWithUnicast()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            final String tags = "1,2";

            launch();

            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder()
                .media(CommonContext.UDP_MEDIA)
                .tags(tags)
                .controlMode(CommonContext.MDC_CONTROL_MODE_MANUAL);

            final String subscriptionChannel = builder.build();

            subscription = clientA.addSubscription(subscriptionChannel, STREAM_ID);
            copySubscription = clientA.addSubscription(subscriptionChannel, STREAM_ID);
            subscription.addDestination(PUB_UNICAST_URI);

            publicationA = clientA.addPublication(PUB_UNICAST_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSend; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                final MutableInteger copyFragmentsRead = new MutableInteger();

                pollForFragment(subscription, fragmentHandler, fragmentsRead);
                pollForFragment(copySubscription, copyFragmentHandler, copyFragmentsRead);
            }

            verifyFragments(fragmentHandler, numMessagesToSend);
            verifyFragments(copyFragmentHandler, numMessagesToSend);
        });
    }

    @Test
    public void shouldSendToSingleDestinationSubscriptionWithMulticast()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;

            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(PUB_MULTICAST_URI);

            publicationA = clientA.addPublication(PUB_MULTICAST_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSend; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);
            }

            verifyFragments(fragmentHandler, numMessagesToSend);
        });
    }

    @Test
    public void shouldSendToSingleDestinationSubscriptionWithDynamicMdc()
    {
        assertTimeoutPreemptively(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;

            launch();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(SUB_MDC_DESTINATION_URI);

            publicationA = clientA.addPublication(PUB_MDC_URI, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSend; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);
            }

            verifyFragments(fragmentHandler, numMessagesToSend);
        });
    }

    @Test
    public void shouldSendToMultipleDestinationSubscriptionWithSameStream()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            final int numMessagesToSendForA = numMessagesToSend / 2;
            final int numMessagesToSendForB = numMessagesToSend / 2;
            final String tags = "1,2";
            final int pubTag = 2;

            launch();

            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder();

            builder
                .clear()
                .tags(tags)
                .media(CommonContext.UDP_MEDIA)
                .endpoint(UNICAST_ENDPOINT_A);

            final String publicationChannelA = builder.build();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(publicationChannelA);

            publicationA = clientA.addPublication(publicationChannelA, STREAM_ID);

            while (subscription.hasNoImages())
            {
                Thread.yield();
                Tests.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSendForA; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);
            }

            final long position = publicationA.position();
            final int initialTermId = publicationA.initialTermId();
            final int positionBitsToShift = Long.numberOfTrailingZeros(publicationA.termBufferLength());
            final int termId = LogBufferDescriptor
                .computeTermIdFromPosition(position, positionBitsToShift, initialTermId);
            final int termOffset = (int)(position & (publicationA.termBufferLength() - 1));

            builder
                .clear()
                .media(CommonContext.UDP_MEDIA)
                .isSessionIdTagged(true)
                .sessionId(pubTag)
                .initialTermId(initialTermId)
                .termId(termId)
                .termOffset(termOffset)
                .endpoint(UNICAST_ENDPOINT_B);

            final String publicationChannelB = builder.build();

            publicationB = clientA.addExclusivePublication(publicationChannelB, STREAM_ID);

            builder
                .clear()
                .media(CommonContext.UDP_MEDIA)
                .endpoint(UNICAST_ENDPOINT_B);

            final String destinationChannel = builder.build();

            subscription.addDestination(destinationChannel);

            for (int i = 0; i < numMessagesToSendForB; i++)
            {
                while (publicationB.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);
            }

            assertEquals(1, subscription.imageCount());
            assertEquals(2, subscription.imageAtIndex(0).activeTransportCount());
            verifyFragments(fragmentHandler, numMessagesToSend);
        });
    }

    @Test
    public void shouldMergeStreamsFromMultiplePublicationsWithSameParams()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final int numMessagesToSend = 30;
            final int numMessagesToSendForA = numMessagesToSend / 2;
            final int numMessagesToSendForB = numMessagesToSend / 2;

            launch();
            launchSecond();

            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder();

            builder
                .clear()
                .media(CommonContext.UDP_MEDIA)
                .endpoint(UNICAST_ENDPOINT_A);

            final String publicationChannelA = builder.build();

            builder
                .clear()
                .media(CommonContext.UDP_MEDIA)
                .endpoint(UNICAST_ENDPOINT_B);

            final String destinationB = builder.build();

            subscription = clientA.addSubscription(SUB_URI, STREAM_ID);
            subscription.addDestination(publicationChannelA);
            subscription.addDestination(destinationB);

            publicationA = clientA.addExclusivePublication(publicationChannelA, STREAM_ID);

            builder
                .clear()
                .media(CommonContext.UDP_MEDIA)
                .initialPosition(0L, publicationA.initialTermId(), publicationA.termBufferLength())
                .sessionId(publicationA.sessionId())
                .endpoint(UNICAST_ENDPOINT_B);

            final String publicationChannelB = builder.build();

            publicationB = clientB.addExclusivePublication(publicationChannelB, STREAM_ID);

            for (int i = 0; i < numMessagesToSendForA; i++)
            {
                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);

                while (publicationB.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                assertEquals(0, subscription.poll(fragmentHandler, 10));
            }

            for (int i = 0; i < numMessagesToSendForB; i++)
            {
                while (publicationB.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                final MutableInteger fragmentsRead = new MutableInteger();
                pollForFragment(subscription, fragmentHandler, fragmentsRead);

                while (publicationA.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                assertEquals(0, subscription.poll(fragmentHandler, 10));
            }

            assertEquals(1, subscription.imageCount());
            assertEquals(2, subscription.imageAtIndex(0).activeTransportCount());
            verifyFragments(fragmentHandler, numMessagesToSend);
        });
    }

    private void pollForFragment(
        final Subscription subscription, final FragmentHandler handler, final MutableInteger fragmentsRead)
    {
        SystemTests.executeUntil(
            () -> fragmentsRead.get() > 0,
            (j) ->
            {
                final int fragments = subscription.poll(handler, 10);
                if (fragments == 0)
                {
                    Thread.yield();
                }

                fragmentsRead.value += fragments;
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(500));
    }

    private void verifyFragments(final FragmentHandler fragmentHandler, final int numMessagesToSend)
    {
        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(MESSAGE_LENGTH),
            any(Header.class));
    }
}
