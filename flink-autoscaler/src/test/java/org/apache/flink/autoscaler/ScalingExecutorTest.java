/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.autoscaler;

import org.apache.flink.api.common.JobID;
import org.apache.flink.autoscaler.config.AutoScalerOptions;
import org.apache.flink.autoscaler.event.TestingEventCollector;
import org.apache.flink.autoscaler.metrics.EvaluatedMetrics;
import org.apache.flink.autoscaler.metrics.EvaluatedScalingMetric;
import org.apache.flink.autoscaler.metrics.ScalingMetric;
import org.apache.flink.autoscaler.state.AutoScalerStateStore;
import org.apache.flink.autoscaler.state.InMemoryAutoScalerStateStore;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.autoscaler.TestingAutoscalerUtils.createDefaultJobAutoScalerContext;
import static org.apache.flink.autoscaler.event.AutoScalerEventHandler.SCALING_REPORT_REASON;
import static org.apache.flink.autoscaler.event.AutoScalerEventHandler.SCALING_SUMMARY_ENTRY;
import static org.apache.flink.autoscaler.event.AutoScalerEventHandler.SCALING_SUMMARY_HEADER_SCALING_DISABLED;
import static org.apache.flink.autoscaler.event.AutoScalerEventHandler.SCALING_SUMMARY_HEADER_SCALING_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test for {@link ScalingExecutor}. */
public class ScalingExecutorTest {

    private JobAutoScalerContext<JobID> context;
    private TestingEventCollector<JobID, JobAutoScalerContext<JobID>> eventCollector;
    private ScalingExecutor<JobID, JobAutoScalerContext<JobID>> scalingDecisionExecutor;

    private InMemoryAutoScalerStateStore<JobID, JobAutoScalerContext<JobID>> stateStore;

    private Configuration conf;

    private static final Map<ScalingMetric, EvaluatedScalingMetric> dummyGlobalMetrics =
            Map.of(
                    ScalingMetric.GC_PRESSURE, EvaluatedScalingMetric.of(Double.NaN),
                    ScalingMetric.HEAP_USAGE, EvaluatedScalingMetric.of(Double.NaN));

    @BeforeEach
    public void setup() {
        eventCollector = new TestingEventCollector<>();
        context = createDefaultJobAutoScalerContext();
        stateStore = new InMemoryAutoScalerStateStore<>();

        scalingDecisionExecutor = new ScalingExecutor<>(eventCollector, stateStore);
        conf = context.getConfiguration();
        conf.set(AutoScalerOptions.STABILIZATION_INTERVAL, Duration.ZERO);
        conf.set(AutoScalerOptions.SCALING_ENABLED, true);
        conf.set(AutoScalerOptions.MAX_SCALE_DOWN_FACTOR, 1.);
        conf.set(AutoScalerOptions.MAX_SCALE_UP_FACTOR, (double) Integer.MAX_VALUE);
        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ZERO);
    }

    @Test
    public void testUtilizationBoundaries() throws Exception {
        // Restart time should not affect utilization boundary
        var conf = context.getConfiguration();
        conf.set(AutoScalerOptions.RESTART_TIME, Duration.ZERO);
        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ZERO);

        var op1 = new JobVertexID();

        conf.set(AutoScalerOptions.TARGET_UTILIZATION, 0.6);
        conf.set(AutoScalerOptions.TARGET_UTILIZATION_BOUNDARY, 0.);

        var evaluated = Map.of(op1, evaluated(1, 70, 100));
        var scalingSummary = Map.of(op1, new ScalingSummary(2, 1, evaluated.get(op1)));
        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        conf.set(AutoScalerOptions.TARGET_UTILIZATION_BOUNDARY, 0.2);
        evaluated = Map.of(op1, evaluated(1, 70, 100));
        scalingSummary = Map.of(op1, new ScalingSummary(2, 1, evaluated.get(op1)));
        assertTrue(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));
        assertTrue(getScaledParallelism(stateStore, context).isEmpty());

        var op2 = new JobVertexID();
        evaluated =
                Map.of(
                        op1, evaluated(1, 70, 100),
                        op2, evaluated(1, 85, 100));
        scalingSummary =
                Map.of(
                        op1,
                        new ScalingSummary(1, 2, evaluated.get(op1)),
                        op2,
                        new ScalingSummary(1, 2, evaluated.get(op2)));

        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        evaluated =
                Map.of(
                        op1, evaluated(1, 70, 100),
                        op2, evaluated(1, 70, 100));
        scalingSummary =
                Map.of(
                        op1,
                        new ScalingSummary(1, 2, evaluated.get(op1)),
                        op2,
                        new ScalingSummary(1, 2, evaluated.get(op2)));
        assertTrue(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        // Test with backlog based scaling
        evaluated = Map.of(op1, evaluated(1, 70, 100, 15));
        scalingSummary = Map.of(op1, new ScalingSummary(1, 2, evaluated.get(op1)));
        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));
    }

    @Test
    public void testVertexesExclusionForScaling() throws Exception {
        var sourceHexString = "0bfd135746ac8efb3cce668b12e16d3a";
        var source = JobVertexID.fromHexString(sourceHexString);
        var filterOperatorHexString = "869fb403873411306404e9f2e4438c0e";
        var filterOperator = JobVertexID.fromHexString(filterOperatorHexString);
        var sinkHexString = "a6b7102b8d3e3a9564998c1ffeb5e2b7";
        var sink = JobVertexID.fromHexString(sinkHexString);

        var conf = context.getConfiguration();
        conf.set(AutoScalerOptions.TARGET_UTILIZATION, .8);
        var metrics =
                new EvaluatedMetrics(
                        Map.of(
                                source,
                                evaluated(10, 80, 100),
                                filterOperator,
                                evaluated(10, 30, 100),
                                sink,
                                evaluated(10, 80, 100)),
                        dummyGlobalMetrics);
        // filter operator should not scale
        conf.set(AutoScalerOptions.VERTEX_EXCLUDE_IDS, List.of(filterOperatorHexString));
        var now = Instant.now();
        assertFalse(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), now));
        // filter operator should scale
        conf.set(AutoScalerOptions.VERTEX_EXCLUDE_IDS, List.of());
        assertTrue(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), now));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testScalingEventsWith0IntervalConfig(boolean scalingEnabled) throws Exception {
        testScalingEvents(scalingEnabled, Duration.ofSeconds(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testScalingEventsWithIntervalConfig(boolean scalingEnabled) throws Exception {
        testScalingEvents(scalingEnabled, Duration.ofSeconds(1800));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testScalingEventsWithDefaultIntervalConfig(boolean scalingEnabled)
            throws Exception {
        testScalingEvents(scalingEnabled, null);
    }

    private void testScalingEvents(boolean scalingEnabled, Duration interval) throws Exception {
        var jobVertexID = new JobVertexID();
        var conf = context.getConfiguration();
        conf.set(AutoScalerOptions.SCALING_ENABLED, scalingEnabled);
        if (interval != null) {
            conf.set(AutoScalerOptions.SCALING_EVENT_INTERVAL, interval);
        }

        var now = Instant.now();
        var metrics =
                new EvaluatedMetrics(
                        Map.of(jobVertexID, evaluated(1, 110, 100)), dummyGlobalMetrics);
        assertEquals(
                scalingEnabled,
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), now));
        assertEquals(
                scalingEnabled,
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), now));
        int expectedSize = (interval == null || interval.toMillis() > 0) && !scalingEnabled ? 1 : 2;
        assertEquals(expectedSize, eventCollector.events.size());

        TestingEventCollector.Event<JobID, JobAutoScalerContext<JobID>> event;
        do {
            event = eventCollector.events.poll();
        } while (!eventCollector.events.isEmpty());

        assertTrue(
                event.getMessage()
                        .contains(
                                String.format(
                                        SCALING_SUMMARY_ENTRY,
                                        jobVertexID,
                                        1,
                                        2,
                                        100.0,
                                        157.0,
                                        110.0)));
        assertTrue(
                event.getMessage()
                        .contains(
                                scalingEnabled
                                        ? SCALING_SUMMARY_HEADER_SCALING_ENABLED
                                        : SCALING_SUMMARY_HEADER_SCALING_DISABLED));
        assertEquals(SCALING_REPORT_REASON, event.getReason());

        metrics =
                new EvaluatedMetrics(
                        Map.of(jobVertexID, evaluated(1, 110, 101)), dummyGlobalMetrics);
        assertEquals(
                scalingEnabled,
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), now));
        var event2 = eventCollector.events.poll();
        assertThat(event2).isNotNull();
        assertThat(event2.getContext()).isSameAs(event.getContext());
        assertEquals(expectedSize + 1, event2.getCount());
        assertEquals(!scalingEnabled, stateStore.getParallelismOverrides(context).isEmpty());
    }

    @Test
    public void testScalingUnderGcPressure() throws Exception {
        var jobVertexID = new JobVertexID();
        conf.set(AutoScalerOptions.SCALING_ENABLED, true);
        conf.set(AutoScalerOptions.GC_PRESSURE_THRESHOLD, 0.5);
        conf.set(AutoScalerOptions.HEAP_USAGE_THRESHOLD, 0.8);

        var vertexMetrics = Map.of(jobVertexID, evaluated(1, 110, 100));
        var metrics =
                new EvaluatedMetrics(
                        vertexMetrics,
                        Map.of(
                                ScalingMetric.GC_PRESSURE,
                                EvaluatedScalingMetric.of(Double.NaN),
                                ScalingMetric.HEAP_USAGE,
                                EvaluatedScalingMetric.of(Double.NaN)));

        // Baseline, no GC/Heap metrics
        assertTrue(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), Instant.now()));

        // Just below the thresholds
        metrics =
                new EvaluatedMetrics(
                        vertexMetrics,
                        Map.of(
                                ScalingMetric.GC_PRESSURE,
                                EvaluatedScalingMetric.of(0.49),
                                ScalingMetric.HEAP_USAGE,
                                new EvaluatedScalingMetric(0.9, 0.79)));
        assertTrue(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), Instant.now()));

        eventCollector.events.clear();

        // GC Pressure above limit
        metrics =
                new EvaluatedMetrics(
                        vertexMetrics,
                        Map.of(
                                ScalingMetric.GC_PRESSURE,
                                EvaluatedScalingMetric.of(0.51),
                                ScalingMetric.HEAP_USAGE,
                                new EvaluatedScalingMetric(0.9, 0.79)));
        assertFalse(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), Instant.now()));
        assertEquals("MemoryPressure", eventCollector.events.poll().getReason());
        assertTrue(eventCollector.events.isEmpty());

        // Heap usage above limit
        metrics =
                new EvaluatedMetrics(
                        vertexMetrics,
                        Map.of(
                                ScalingMetric.GC_PRESSURE,
                                EvaluatedScalingMetric.of(0.49),
                                ScalingMetric.HEAP_USAGE,
                                new EvaluatedScalingMetric(0.6, 0.81)));
        assertFalse(
                scalingDecisionExecutor.scaleResource(
                        context, metrics, new HashMap<>(), new ScalingTracking(), Instant.now()));
        assertEquals("MemoryPressure", eventCollector.events.poll().getReason());
        assertTrue(eventCollector.events.isEmpty());
    }

    private Map<ScalingMetric, EvaluatedScalingMetric> evaluated(
            int parallelism, double target, double procRate, double catchupRate) {
        var metrics = new HashMap<ScalingMetric, EvaluatedScalingMetric>();
        metrics.put(ScalingMetric.PARALLELISM, EvaluatedScalingMetric.of(parallelism));
        metrics.put(ScalingMetric.MAX_PARALLELISM, EvaluatedScalingMetric.of(720));
        metrics.put(ScalingMetric.TARGET_DATA_RATE, new EvaluatedScalingMetric(target, target));
        metrics.put(ScalingMetric.CATCH_UP_DATA_RATE, EvaluatedScalingMetric.of(catchupRate));
        metrics.put(
                ScalingMetric.TRUE_PROCESSING_RATE, new EvaluatedScalingMetric(procRate, procRate));

        var restartTime = context.getConfiguration().get(AutoScalerOptions.RESTART_TIME);
        ScalingMetricEvaluator.computeProcessingRateThresholds(
                metrics, context.getConfiguration(), false, restartTime);
        return metrics;
    }

    private Map<ScalingMetric, EvaluatedScalingMetric> evaluated(
            int parallelism, double target, double procRate) {
        return evaluated(parallelism, target, procRate, 0.);
    }

    protected static <KEY, Context extends JobAutoScalerContext<KEY>>
            Map<JobVertexID, Integer> getScaledParallelism(
                    AutoScalerStateStore<KEY, Context> stateStore, Context context)
                    throws Exception {
        return stateStore.getParallelismOverrides(context).entrySet().stream()
                .collect(
                        Collectors.toMap(
                                e -> JobVertexID.fromHexString(e.getKey()),
                                e -> Integer.valueOf(e.getValue())));
    }
}
