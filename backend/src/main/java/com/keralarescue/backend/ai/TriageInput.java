package com.keralarescue.backend.ai;

/** What the triage step is allowed to see about a request (no phone number, no tracking token). */
public record TriageInput(String emergencyType, String priority, String description, String conditionDetails,
                          int people, int injured, byte[] imageBytes, String imageContentType) { }
