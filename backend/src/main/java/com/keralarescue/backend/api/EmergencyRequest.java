package com.keralarescue.backend.api;

public class EmergencyRequest {
    public String emergencyId;
    public String trackingToken;
    public String emergencyType;
    public String priority;
    public String status;
    public String location;
    public String description;
    public String phone;
    public String assignedTeam;
    public Double latitude;
    public Double longitude;
    public Boolean critical;
    public Boolean locationUnavailable;
    public Integer peopleNeedingHelp;
    public Integer injuredCount;
    public String conditionDetails;
    public String imageBase64;
    public String imageContentType;
}
