package com.poc.a11y.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ScanResult {

    private Instant scannedAt = Instant.now();
    private String platformName;
    private String deviceName;
    private int elementsScanned;
    private int totalIssues;
    private Map<Severity, Long> severityCounts = new EnumMap<>(Severity.class);
    private List<Issue> issues;

    public ScanResult() {}

    public ScanResult(String platformName, String deviceName, int elementsScanned, List<Issue> issues) {
        this.platformName = platformName;
        this.deviceName = deviceName;
        this.elementsScanned = elementsScanned;
        this.issues = issues;
        this.totalIssues = issues.size();
        for (Severity s : Severity.values()) {
            severityCounts.put(s, issues.stream().filter(i -> i.getSeverity() == s).count());
        }
    }

    public Instant getScannedAt() { return scannedAt; }
    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }

    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public int getElementsScanned() { return elementsScanned; }
    public void setElementsScanned(int elementsScanned) { this.elementsScanned = elementsScanned; }

    public int getTotalIssues() { return totalIssues; }
    public void setTotalIssues(int totalIssues) { this.totalIssues = totalIssues; }

    public Map<Severity, Long> getSeverityCounts() { return severityCounts; }
    public void setSeverityCounts(Map<Severity, Long> severityCounts) { this.severityCounts = severityCounts; }

    public List<Issue> getIssues() { return issues; }
    public void setIssues(List<Issue> issues) { this.issues = issues; }
}
