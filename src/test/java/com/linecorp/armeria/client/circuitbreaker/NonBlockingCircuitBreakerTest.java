/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.Test;

import com.google.common.testing.FakeTicker;

public class NonBlockingCircuitBreakerTest {

    private static final String remoteServiceName = "testservice";

    private static final FakeTicker ticker = new FakeTicker();

    private static final Duration circuitOpenWindow = Duration.ofSeconds(1);

    private static final Duration trialRequestInterval = Duration.ofSeconds(1);

    private static final Duration counterUpdateInterval = Duration.ofSeconds(1);

    private static NonBlockingCircuitBreaker create(long minimumRequestThreshold, double failureRateThreshold) {
        return (NonBlockingCircuitBreaker) new CircuitBreakerBuilder(remoteServiceName)
                .failureRateThreshold(failureRateThreshold)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .trialRequestInterval(trialRequestInterval)
                .counterSlidingWindow(Duration.ofSeconds(10))
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();
    }

    private static CircuitBreaker closedState(long minimumRequestThreshold, double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        assertThat(cb.state().isClosed(), is(true));
        assertThat(cb.canRequest(), is(true));
        return cb;
    }

    private static NonBlockingCircuitBreaker openState(long minimumRequestThreshold,
                                                       double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        cb.onSuccess();
        cb.onFailure();
        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isOpen(), is(true));
        assertThat(cb.canRequest(), is(false));
        return cb;
    }

    private static NonBlockingCircuitBreaker halfOpenState(long minimumRequestThreshold,
                                                           double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = openState(minimumRequestThreshold, failureRateThreshold);

        ticker.advance(circuitOpenWindow.toNanos());

        assertThat(cb.state().isHalfOpen(), is(false));
        assertThat(cb.canRequest(), is(true)); // first request is allowed
        assertThat(cb.state().isHalfOpen(), is(true));
        assertThat(cb.canRequest(), is(false)); // seconds request is refused
        return cb;
    }

    @Test
    public void testClosed() {
        closedState(2, 0.5);
    }

    @Test
    public void testMinimumRequestThreshold() {
        NonBlockingCircuitBreaker cb = create(4, 0.5);
        assertThat(cb.state().isClosed() && cb.canRequest(), is(true));

        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isClosed(), is(true));
        assertThat(cb.canRequest(), is(true));

        cb.onFailure();
        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isOpen(), is(true));
        assertThat(cb.canRequest(), is(false));
    }

    @Test
    public void testFailureRateThreshold() {
        NonBlockingCircuitBreaker cb = create(10, 0.5);

        for (int i = 0; i < 10; i++) {
            cb.onSuccess();
        }
        for (int i = 0; i < 9; i++) {
            cb.onFailure();
        }

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isClosed(), is(true)); // 10 vs 9 (0.47)
        assertThat(cb.canRequest(), is(true));

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isClosed(), is(true)); // 10 vs 10 (0.5)
        assertThat(cb.canRequest(), is(true));

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isOpen(), is(true)); // 10 vs 11 (0.52)
        assertThat(cb.canRequest(), is(false));
    }

    @Test
    public void testClosedToOpen() {
        openState(2, 0.5);
    }

    @Test
    public void testOpenToHalfOpen() {
        halfOpenState(2, 0.5);
    }

    @Test
    public void testHalfOpenToClosed() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onSuccess();

        assertThat(cb.state().isClosed(), is(true));
        assertThat(cb.canRequest(), is(true));
    }

    @Test
    public void testHalfOpenToOpen() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onFailure();

        assertThat(cb.state().isOpen(), is(true));
        assertThat(cb.canRequest(), is(false));
    }

    @Test
    public void testHalfOpenRetryRequest() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        ticker.advance(trialRequestInterval.toNanos());

        assertThat(cb.state().isHalfOpen(), is(true));
        assertThat(cb.canRequest(), is(true)); // first request is allowed
        assertThat(cb.state().isHalfOpen(), is(true));
        assertThat(cb.canRequest(), is(false)); // seconds request is refused
    }

    @Test
    public void testFailureOfExceptionFilter() {
        NonBlockingCircuitBreaker cb = (NonBlockingCircuitBreaker) new CircuitBreakerBuilder()
                .exceptionFilter(cause -> {throw new Exception();})
                .ticker(ticker)
                .build();
        cb.onFailure(new Exception());
    }
}
