package com.keralarescue.backend.ai;

import java.util.List;

/** Outcome of AI (or rule-based) triage. {@code source} is CLAUDE or RULES. */
public record TriageResult(String priority, String type, String summary, String translation, List<String> flags,
                           double confidence, String source) {

    public static final List<String> PRIORITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public static int rank(String priority) { return priority == null ? -1 : PRIORITIES.indexOf(priority.trim().toUpperCase()); }

    public static String higher(String a, String b) { return rank(a) >= rank(b) ? a : b; }
}
