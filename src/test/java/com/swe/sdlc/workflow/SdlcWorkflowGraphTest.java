package com.swe.sdlc.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdlcWorkflowGraphTest {
    @Test
    void ordersSequentialGatesAndSynchronizesParallelArtifactStages() {
        var layers = new SdlcWorkflowGraph().executionLayers();

        assertEquals(Set.of(SdlcWorkflowGraph.Stage.REQUIREMENT_ANALYSIS), Set.copyOf(layers.get(0)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.ARCHITECTURE), Set.copyOf(layers.get(1)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.TASK_DECOMPOSITION), Set.copyOf(layers.get(2)));
        assertEquals(Set.of(
                SdlcWorkflowGraph.Stage.IMPLEMENTATION,
                SdlcWorkflowGraph.Stage.TESTS,
                SdlcWorkflowGraph.Stage.DOCUMENTATION), Set.copyOf(layers.get(3)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.SYNCHRONIZATION), Set.copyOf(layers.get(4)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.VALIDATION), Set.copyOf(layers.get(5)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.HUMAN_APPROVAL), Set.copyOf(layers.get(6)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.RELEASE_READY), Set.copyOf(layers.get(7)));

        var dependencies = new SdlcWorkflowGraph().dependencies();
        assertTrue(dependencies.get(SdlcWorkflowGraph.Stage.SYNCHRONIZATION)
                .containsAll(Set.of(SdlcWorkflowGraph.Stage.IMPLEMENTATION,
                        SdlcWorkflowGraph.Stage.TESTS,
                        SdlcWorkflowGraph.Stage.DOCUMENTATION)));
        assertEquals(Set.of(SdlcWorkflowGraph.Stage.SYNCHRONIZATION),
                dependencies.get(SdlcWorkflowGraph.Stage.VALIDATION));
    }
}