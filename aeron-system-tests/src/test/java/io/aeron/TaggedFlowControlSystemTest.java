package io.aeron;

import io.aeron.driver.*;
import io.aeron.driver.status.SenderLimit;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.status.HeartbeatTimestamp;
import io.aeron.test.MediaDriverTestWatcher;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.SystemUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.aeron.FlowControlTests.waitForConnectionAndStatusMessages;
import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TaggedFlowControlSystemTest
{
    private static final String MULTICAST_URI = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost";
    private static final int STREAM_ID = 1001;

    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int NUM_MESSAGES_PER_TERM = 64;
    private static final int MESSAGE_LENGTH =
        (TERM_BUFFER_LENGTH / NUM_MESSAGES_PER_TERM) - DataHeaderFlyweight.HEADER_LENGTH;
    private static final String ROOT_DIR =
        SystemUtil.tmpDirName() + "aeron-system-tests-" + UUID.randomUUID().toString() + File.separator;

    private final MediaDriver.Context driverAContext = new MediaDriver.Context();
    private final MediaDriver.Context driverBContext = new MediaDriver.Context();

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driverA;
    private TestMediaDriver driverB;
    private Publication publication;
    private Subscription subscriptionA;
    private Subscription subscriptionB;

    private static final long DEFAULT_RECEIVER_TAG = new UnsafeBuffer(TaggedMulticastFlowControl.PREFERRED_ASF_BYTES)
        .getLong(0, ByteOrder.LITTLE_ENDIAN);

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final FragmentHandler fragmentHandlerA = mock(FragmentHandler.class);
    private final FragmentHandler fragmentHandlerB = mock(FragmentHandler.class);

    @RegisterExtension
    public MediaDriverTestWatcher testWatcher = new MediaDriverTestWatcher();

    private void launch()
    {
        buffer.putInt(0, 1);

        final String baseDirA = ROOT_DIR + "A";
        final String baseDirB = ROOT_DIR + "B";

        driverAContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirA)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverBContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirB)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverA = TestMediaDriver.launch(driverAContext, testWatcher);
        driverB = TestMediaDriver.launch(driverBContext, testWatcher);
        clientA = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverAContext.aeronDirectoryName()));

        clientB = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverBContext.aeronDirectoryName()));
    }

    @AfterEach
    public void after()
    {
        CloseHelper.quietCloseAll(clientB, clientA, driverB, driverA);
        IoUtil.delete(new File(ROOT_DIR), true);
    }

    private static Stream<Arguments> strategyConfigurations()
    {
        return Stream.of(
            Arguments.of(new TaggedMulticastFlowControlSupplier(), DEFAULT_RECEIVER_TAG, null, "", ""),
            Arguments.of(new TaggedMulticastFlowControlSupplier(), null, null, "", "|rtag=-1"),
            Arguments.of(new TaggedMulticastFlowControlSupplier(), null, 2004L, "", "|rtag=2004"),
            Arguments.of(null, DEFAULT_RECEIVER_TAG, null, "|fc=tagged", ""),
            Arguments.of(null, 2020L, 2020L, "|fc=tagged", ""),
            Arguments.of(null, null, null, "|fc=tagged,g:123", "|rtag=123"));
    }

    private static class State
    {
        private int numMessagesToSend;
        private int numMessagesLeftToSend;
        private int numFragmentsReadFromA;
        private int numFragmentsReadFromB;
        private boolean isBClosed = false;

        public String toString()
        {
            return "State{" +
                   "numMessagesToSend=" + numMessagesToSend +
                   ", numMessagesLeftToSend=" + numMessagesLeftToSend +
                   ", numFragmentsReadFromA=" + numFragmentsReadFromA +
                   ", numFragmentsReadFromB=" + numFragmentsReadFromB +
                   ", isBClosed=" + isBClosed +
                   '}';
        }
    }

    @ParameterizedTest
    @MethodSource("strategyConfigurations")
    public void shouldSlowToTaggedWithMulticastFlowControlStrategy(
        final FlowControlSupplier flowControlSupplier,
        final Long receiverTag,
        final Long flowControlGroupReceiverTag,
        final String publisherUriParams,
        final String subscriptionBUriParams)
    {
        Tests.withTimeout(ofSeconds(10));
        final State state = new State();
        state.numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
        state.numMessagesLeftToSend = state.numMessagesToSend;
        state.numFragmentsReadFromB = 0;

        driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
        if (null != flowControlSupplier)
        {
            driverAContext.multicastFlowControlSupplier(flowControlSupplier);
        }
        if (null != flowControlGroupReceiverTag)
        {
            driverAContext.flowControlGroupReceiverTag(flowControlGroupReceiverTag);
        }
        if (null != receiverTag)
        {
            driverBContext.receiverTag(receiverTag);
        }
        driverAContext.flowControlReceiverGroupMinSize(1);

        launch();

        subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(MULTICAST_URI + subscriptionBUriParams, STREAM_ID);
        publication = clientA.addPublication(MULTICAST_URI + publisherUriParams, STREAM_ID);

        Tests.yieldingWait(subscriptionA::isConnected);
        Tests.yieldingWait(subscriptionB::isConnected);
        Tests.yieldingWait(publication::isConnected);

        for (long i = 0; state.numFragmentsReadFromB < state.numMessagesToSend; i++)
        {
            if (state.numMessagesLeftToSend > 0)
            {
                final long result = publication.offer(buffer, 0, buffer.capacity());
                if (result >= 0L)
                {
                    state.numMessagesLeftToSend--;
                }
                else if (Publication.NOT_CONNECTED == result)
                {
                    fail("Publication not connected, numMessagesLeftToSend=" + state.numMessagesLeftToSend);
                }
                else
                {
                    Tests.yieldingWait(state::toString);
                }
            }

            Tests.yieldingWait(state::toString);

            // A keeps up
            pollWithTimeout(subscriptionA, fragmentHandlerA, 10, state::toString);

            // B receives slowly
            if ((i % 2) == 0)
            {
                final int bFragments = pollWithTimeout(subscriptionB, fragmentHandlerB, 1, state::toString);
                if (0 == bFragments && !subscriptionB.isConnected())
                {
                    if (subscriptionB.isClosed())
                    {
                        fail("Subscription B is closed, numFragmentsFromB=" + state.numFragmentsReadFromB);
                    }

                    fail("Subscription B not connected, numFragmentsFromB=" + state.numFragmentsReadFromB);
                }

                state.numFragmentsReadFromB += bFragments;
            }
        }

        verify(fragmentHandlerB, times(state.numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(MESSAGE_LENGTH),
            any(Header.class));
    }

    @Test
    public void shouldRemoveDeadTaggedReceiverWithTaggedMulticastFlowControlStrategy()
    {
        Tests.withTimeout(ofSeconds(10));
        final State state = new State();
        state.numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
        state.numMessagesLeftToSend = state.numMessagesToSend;
        state.numFragmentsReadFromA = 0;
        state.numFragmentsReadFromB = 0;
        state.isBClosed = false;

        driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

        launch();

        subscriptionA = clientA.addSubscription(MULTICAST_URI + "|rtag=123", STREAM_ID);
        subscriptionB = clientB.addSubscription(MULTICAST_URI + "|rtag=123", STREAM_ID);
        publication = clientA.addPublication(MULTICAST_URI + "|fc=tagged,g:123,t:1s", STREAM_ID);

        Tests.yieldingWait(subscriptionA::isConnected);
        Tests.yieldingWait(subscriptionB::isConnected);
        Tests.yieldingWait(publication::isConnected);

        while (state.numFragmentsReadFromA < state.numMessagesToSend)
        {
            if (state.numMessagesLeftToSend > 0)
            {
                final long position = publication.offer(buffer, 0, buffer.capacity());
                if (position >= 0L)
                {
                    state.numMessagesLeftToSend--;
                }
                else
                {
                    Tests.yieldingWait("position: %d, state: %s", position, state);
                }
            }

            // A keeps up
            state.numFragmentsReadFromA += pollWithTimeout(subscriptionA, fragmentHandlerA, 10, state::toString);

            // B receives up to 1/8 of the messages, then stops
            if (state.numFragmentsReadFromB < (state.numMessagesToSend / 8))
            {
                state.numFragmentsReadFromB += pollWithTimeout(
                    subscriptionB, fragmentHandlerB, 10, state::toString);
            }
            else if (!state.isBClosed)
            {
                subscriptionB.close();
                state.isBClosed = true;
            }
        }

        verify(fragmentHandlerA, times(state.numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(MESSAGE_LENGTH),
            any(Header.class));
    }

    private int pollWithTimeout(
        final Subscription subscription,
        final FragmentHandler fragmentHandler,
        final int fragmentLimit,
        final Supplier<String> message)
    {
        final int numFragments = subscription.poll(fragmentHandler, fragmentLimit);
        if (0 == numFragments)
        {
            Tests.yieldingWait(message.get());
        }
        return numFragments;
    }

    @SuppressWarnings("methodlength")
    @Test
    void shouldPreventConnectionUntilRequiredGroupSizeMatchTagIsMet()
    {
        final Long receiverTag = 2701L;
        final Integer groupSize = 3;

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("224.20.30.39:24326")
            .networkInterface("localhost");

        final String uriWithReceiverTag = builder
            .receiverTag(receiverTag)
            .flowControl((String)null)
            .build();

        final String uriPlain = builder
            .receiverTag((Long)null)
            .flowControl((String)null)
            .build();

        final String uriWithTaggedFlowControl = builder
            .receiverTag((Long)null)
            .taggedFlowControl(receiverTag, groupSize, null)
            .build();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
        {
            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

            launch();

            TestMediaDriver driverC = null;
            Aeron clientC = null;

            TestMediaDriver driverD = null;
            Aeron clientD = null;

            Publication publication = null;
            Subscription subscription0 = null;
            Subscription subscription1 = null;
            Subscription subscription2 = null;
            Subscription subscription3 = null;
            Subscription subscription4 = null;
            Subscription subscription5 = null;

            try
            {
                driverC = TestMediaDriver.launch(
                    new MediaDriver.Context().publicationTermBufferLength(TERM_BUFFER_LENGTH)
                        .aeronDirectoryName(ROOT_DIR + "C")
                        .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                        .errorHandler(Throwable::printStackTrace)
                        .threadingMode(ThreadingMode.SHARED),
                    testWatcher);

                clientC = Aeron.connect(
                    new Aeron.Context()
                        .errorHandler(Throwable::printStackTrace)
                        .aeronDirectoryName(driverC.aeronDirectoryName()));

                driverD = TestMediaDriver.launch(
                    new MediaDriver.Context().publicationTermBufferLength(TERM_BUFFER_LENGTH)
                        .aeronDirectoryName(ROOT_DIR + "D")
                        .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                        .errorHandler(Throwable::printStackTrace)
                        .threadingMode(ThreadingMode.SHARED),
                    testWatcher);

                clientD = Aeron.connect(
                    new Aeron.Context()
                        .errorHandler(Throwable::printStackTrace)
                        .aeronDirectoryName(driverD.aeronDirectoryName()));

                publication = clientA.addPublication(uriWithTaggedFlowControl, STREAM_ID);

                subscription0 = clientA.addSubscription(uriPlain, STREAM_ID);
                subscription1 = clientA.addSubscription(uriPlain, STREAM_ID);
                subscription2 = clientA.addSubscription(uriPlain, STREAM_ID);
                subscription3 = clientB.addSubscription(uriWithReceiverTag, STREAM_ID);
                subscription4 = clientC.addSubscription(uriWithReceiverTag, STREAM_ID);

                waitForConnectionAndStatusMessages(
                    clientA.countersReader(),
                    subscription0, subscription1, subscription2, subscription3, subscription4);

                assertFalse(publication.isConnected());

                subscription5 = clientD.addSubscription(uriWithReceiverTag, STREAM_ID);

                // Should now have 3 receivers and publication should eventually be connected.
                while (!publication.isConnected())
                {
                    Tests.sleep(1);
                }

                subscription5.close();
                subscription5 = null;

                // Lost a receiver and publication should eventually be disconnected.
                while (publication.isConnected())
                {
                    Tests.sleep(1);
                }

                subscription5 = clientD.addSubscription(uriWithReceiverTag, STREAM_ID);

                // Aaaaaand reconnect.
                while (!publication.isConnected())
                {
                    Tests.sleep(1);
                }
            }
            finally
            {
                CloseHelper.closeAll(
                    publication,
                    subscription0, subscription1, subscription2, subscription3, subscription4, subscription5,
                    clientC, clientD,
                    driverC, driverD
                );
            }
        });
    }

    @Test
    void shouldPreventConnectionUntilAtLeastOneSubscriberConnectedWithRequiredGroupSizeZero()
    {
        final Long receiverTag = 2701L;
        final Integer groupSize = 0;

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("224.20.30.41:24326")
            .networkInterface("localhost");

        final String plainUri = builder.build();

        final String uriWithReceiverTag = builder
            .receiverTag(receiverTag)
            .flowControl((String)null)
            .build();

        final String uriWithTaggedFlowControl = builder
            .receiverTag((Long)null)
            .taggedFlowControl(receiverTag, groupSize, null)
            .build();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
        {
            launch();

            publication = clientA.addPublication(uriWithTaggedFlowControl, STREAM_ID);
            final Publication otherPublication = clientA.addPublication(plainUri, STREAM_ID + 1);

            final Subscription otherSubscription = clientA.addSubscription(plainUri, STREAM_ID + 1);

            while (!otherPublication.isConnected())
            {
                Tests.sleep(1);
            }
            // We know another publication on the same channel is connected

            assertFalse(publication.isConnected());

            subscriptionA = clientA.addSubscription(uriWithReceiverTag, STREAM_ID);

            while (!publication.isConnected())
            {
                Tests.sleep(1);
            }
        });
    }

    @Test
    public void shouldHandleSenderLimitCorrectlyWithMinGroupSize()
    {
        final String publisherUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost|fc=tagged,g:123/1";
        final String groupSubscriberUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost|rtag=123";
        final String subscriberUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost";
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

            launch();

            publication = clientA.addPublication(publisherUri, STREAM_ID);

            final CountersReader countersReader = clientA.countersReader();

            final int senderLimitCounterId = HeartbeatTimestamp.findCounterIdByRegistrationId(
                countersReader, SenderLimit.SENDER_LIMIT_TYPE_ID, publication.registrationId);
            final long currentSenderLimit = countersReader.getCounterValue(senderLimitCounterId);

            subscriptionA = clientA.addSubscription(subscriberUri, STREAM_ID);

            waitForConnectionAndStatusMessages(countersReader, subscriptionA);

            assertEquals(currentSenderLimit, countersReader.getCounterValue(senderLimitCounterId));

            subscriptionB = clientB.addSubscription(groupSubscriberUri, STREAM_ID);

            while (currentSenderLimit == countersReader.getCounterValue(senderLimitCounterId))
            {
                Tests.sleep(1);
            }
        });
    }
}
