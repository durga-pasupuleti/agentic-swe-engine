package com.swe.sdlc.workflow;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SdlcWorkflowGraph {
    public enum Stage {
        REQUIREMENT_ANALYSIS,
        ARCHITECTURE,
        TASK_DECOMPOSITION,
        IMPLEMENTATION,
        TESTS,
        DOCUMENTATION,
        VALIDATION,
        HUMAN_APPROVAL,
        RELEASE_READY
    }

    private static final Map<Stage, Set<Stage>> DEPENDENCIES = buildDependencies();

    public Map<Stage, Set<Stage>> dependencies() {
        return DEPENDENCIES;
    }

    public List<List<Stage>> executionLayers() {
        EnumSet<Stage> completed = EnumSet.noneOf(Stage.class);
        EnumSet<Stage> remaining = EnumSet.allOf(Stage.class);
        List<List<Stage>> layers = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<Stage> ready = remaining.stream()
                    .filter(stage -> completed.containsAll(DEPENDENCIES.get(stage)))
                    .toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Workflow graph contains a dependency cycle");
            }
            layers.add(ready);
            completed.addAll(ready);
            remaining.removeAll(ready);
        }
        return layers.stream().map(List::copyOf).toList();
    }

    private static Map<Stage, Set<Stage>> buildDependencies() {
        Map<Stage, Set<Stage>> graph = new LinkedHashMap<>();
        graph.put(Stage.REQUIREMENT_ANALYSIS, Set.of());
        graph.put(Stage.ARCHITECTURE, Set.of(Stage.REQUIREMENT_ANALYSIS));
        graph.put(Stage.TASK_DECOMPOSITION, Set.of(Stage.ARCHITECTURE));
        graph.put(Stage.IMPLEMENTATION, Set.of(Stage.TASK_DECOMPOSITION));
        graph.put(Stage.TESTS, Set.of(Stage.TASK_DECOMPOSITION));
        graph.put(Stage.DOCUMENTATION, Set.of(Stage.TASK_DECOMPOSITION));
        graph.put(Stage.VALIDATION, Set.of(Stage.IMPLEMENTATION, Stage.TESTS, Stage.DOCUMENTATION));
        graph.put(Stage.HUMAN_APPROVAL, Set.of(Stage.VALIDATION));
        graph.put(Stage.RELEASE_READY, Set.of(Stage.HUMAN_APPROVAL));
        return Map.copyOf(graph);
    }
}