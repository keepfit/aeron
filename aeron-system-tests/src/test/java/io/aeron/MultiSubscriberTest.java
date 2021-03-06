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
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

public class MultiSubscriberTest
{
    private static final String CHANNEL_1 = "aeron:udp?endpoint=localhost:24325|fruit=banana";
    private static final String CHANNEL_2 = "aeron:udp?endpoint=localhost:24325|fruit=apple";
    private static final int STREAM_ID = 1001;
    private static final int FRAGMENT_COUNT_LIMIT = 10;

    private final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
        .errorHandler(Throwable::printStackTrace)
        .dirDeleteOnShutdown(true)
        .threadingMode(ThreadingMode.SHARED));

    private final Aeron aeron = Aeron.connect();

    @AfterEach
    public void after()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(driver);
    }

    @Test
    public void shouldReceiveMessageOnSeparateSubscriptions()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final FragmentHandler mockFragmentHandlerOne = mock(FragmentHandler.class);
            final FragmentHandler mockFragmentHandlerTwo = mock(FragmentHandler.class);

            final FragmentAssembler adapterOne = new FragmentAssembler(mockFragmentHandlerOne);
            final FragmentAssembler adapterTwo = new FragmentAssembler(mockFragmentHandlerTwo);

            try (Subscription subscriptionOne = aeron.addSubscription(CHANNEL_1, STREAM_ID);
                Subscription subscriptionTwo = aeron.addSubscription(CHANNEL_2, STREAM_ID);
                Publication publication = aeron.addPublication(CHANNEL_1, STREAM_ID))
            {
                final byte[] expectedBytes = "Hello, World! here is a small message".getBytes();
                final UnsafeBuffer srcBuffer = new UnsafeBuffer(expectedBytes);

                assertEquals(0, subscriptionOne.poll(adapterOne, FRAGMENT_COUNT_LIMIT));
                assertEquals(0, subscriptionTwo.poll(adapterTwo, FRAGMENT_COUNT_LIMIT));

                while (!subscriptionOne.isConnected() || !subscriptionTwo.isConnected())
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                while (publication.offer(srcBuffer) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                while (subscriptionOne.poll(adapterOne, FRAGMENT_COUNT_LIMIT) == 0)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                while (subscriptionTwo.poll(adapterTwo, FRAGMENT_COUNT_LIMIT) == 0)
                {
                    Thread.yield();
                    Tests.checkInterruptedStatus();
                }

                verifyData(srcBuffer, mockFragmentHandlerOne);
                verifyData(srcBuffer, mockFragmentHandlerTwo);
            }
        });
    }

    private void verifyData(final UnsafeBuffer srcBuffer, final FragmentHandler mockFragmentHandler)
    {
        final ArgumentCaptor<DirectBuffer> bufferArg = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<Integer> offsetArg = ArgumentCaptor.forClass(Integer.class);

        verify(mockFragmentHandler, times(1)).onFragment(
            bufferArg.capture(), offsetArg.capture(), eq(srcBuffer.capacity()), any(Header.class));

        final DirectBuffer capturedBuffer = bufferArg.getValue();
        final int offset = offsetArg.getValue();
        for (int i = 0; i < srcBuffer.capacity(); i++)
        {
            final int index = offset + i;
            assertEquals(srcBuffer.getByte(i), capturedBuffer.getByte(index), "same at " + index);
        }
    }
}
