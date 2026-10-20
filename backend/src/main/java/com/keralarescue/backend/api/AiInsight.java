package com.keralarescue.backend.api;

import java.util.List;

/** AI (or rule-based) triage attached to a request. Advisory only: dispatchers decide. */
public record AiInsight(String priority, String type, String summary, String translation, List<String> flags,
                        Double confidence, String source, String duplicateOf, String updatedAt) { }
