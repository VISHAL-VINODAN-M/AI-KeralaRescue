package com.keralarescue.backend.api;

public record EmergencyView(
        String emergencyId,
        String trackingToken,
        String emergencyType,
        String priority,
        String status,
        String location,
        String description,
        String phone,
        String assignedTeam,
        Double latitude,
        Double longitude,
        boolean critical,
        boolean locationUnavailable,
        int peopleNeedingHelp,
        int injuredCount,
        String conditionDetails,
        String imageContentType,
        boolean hasImage,
        String estimatedArrival,
        String helperAssignedAt,
        String helperArrivedAt,
        String createdAt,
        String updatedAt,
        AiInsight ai
) { }
