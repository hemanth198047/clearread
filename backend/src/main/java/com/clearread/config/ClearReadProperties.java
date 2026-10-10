package com.clearread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "clearread")
public class ClearReadProperties {

    private final Branding branding = new Branding();
    private final Limits limits = new Limits();
    private final Storage storage = new Storage();
    private final Jobs jobs = new Jobs();
    private final Cors cors = new Cors();

    public Branding getBranding() {
        return branding;
    }

    public Limits getLimits() {
        return limits;
    }

    public Storage getStorage() {
        return storage;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Branding {
        private String productName = "ClearRead";
        private String primaryColor = "#1a5f4a";
        private String secondaryColor = "#c4a35a";
        private String logoUrl = "";

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getPrimaryColor() {
            return primaryColor;
        }

        public void setPrimaryColor(String primaryColor) {
            this.primaryColor = primaryColor;
        }

        public String getSecondaryColor() {
            return secondaryColor;
        }

        public void setSecondaryColor(String secondaryColor) {
            this.secondaryColor = secondaryColor;
        }

        public String getLogoUrl() {
            return logoUrl;
        }

        public void setLogoUrl(String logoUrl) {
            this.logoUrl = logoUrl;
        }
    }

    public static class Limits {
        private long maxUploadBytes = 10 * 1024 * 1024;

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }
    }

    public static class Storage {
        private String endpoint = "http://localhost:9000";
        private String region = "us-east-1";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "clearread";
        private boolean pathStyle = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }
    }

    public static class Jobs {
        private long pollIntervalMs = 1000;
        private int leaseSeconds = 60;
        private String workerId = "local-api";

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
