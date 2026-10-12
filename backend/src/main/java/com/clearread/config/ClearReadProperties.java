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
    private final Fetch fetch = new Fetch();
    private final Ai ai = new Ai();

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

    public Fetch getFetch() {
        return fetch;
    }

    public Ai getAi() {
        return ai;
    }

    public static class Branding {
        private String productName = "ClearRead";
        private String primaryColor = "#1a5f4a";
        private String secondaryColor = "#c4a35a";
        private String logoUrl = "";
        /** When true, SPA shows local demo account credentials (hide in production). */
        private boolean showDemoAccounts = true;

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

        public boolean isShowDemoAccounts() {
            return showDemoAccounts;
        }

        public void setShowDemoAccounts(boolean showDemoAccounts) {
            this.showDemoAccounts = showDemoAccounts;
        }
    }

    public static class Limits {
        private long maxUploadBytes = 10 * 1024 * 1024;
        private int maxNestingDepth = 64;
        private int maxCsvRows = 50_000;
        /** Per-client IP HTTP requests per minute; 0 disables. */
        private int apiRequestsPerMinute = 600;

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public int getMaxNestingDepth() {
            return maxNestingDepth;
        }

        public void setMaxNestingDepth(int maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
        }

        public int getMaxCsvRows() {
            return maxCsvRows;
        }

        public void setMaxCsvRows(int maxCsvRows) {
            this.maxCsvRows = maxCsvRows;
        }

        public int getApiRequestsPerMinute() {
            return apiRequestsPerMinute;
        }

        public void setApiRequestsPerMinute(int apiRequestsPerMinute) {
            this.apiRequestsPerMinute = apiRequestsPerMinute;
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
        private int batchSize = 5;
        private int maxConcurrentPerTenant = 3;
        private boolean enabled = true;

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

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxConcurrentPerTenant() {
            return maxConcurrentPerTenant;
        }

        public void setMaxConcurrentPerTenant(int maxConcurrentPerTenant) {
            this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Fetch {
        /** Private / loopback / link-local / metadata blocked unless true. */
        private boolean allowPrivateNetwork = false;
        private int maxRedirects = 3;
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 15_000;
        private int totalTimeoutMs = 30_000;
        private long maxBytes = 10 * 1024 * 1024;
        private long maxDecompressedBytes = 10 * 1024 * 1024;
        private List<Integer> allowedPorts = new ArrayList<>(List.of(80, 443));

        public boolean isAllowPrivateNetwork() {
            return allowPrivateNetwork;
        }

        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
            this.allowPrivateNetwork = allowPrivateNetwork;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getTotalTimeoutMs() {
            return totalTimeoutMs;
        }

        public void setTotalTimeoutMs(int totalTimeoutMs) {
            this.totalTimeoutMs = totalTimeoutMs;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getMaxDecompressedBytes() {
            return maxDecompressedBytes;
        }

        public void setMaxDecompressedBytes(long maxDecompressedBytes) {
            this.maxDecompressedBytes = maxDecompressedBytes;
        }

        public List<Integer> getAllowedPorts() {
            return allowedPorts;
        }

        public void setAllowedPorts(List<Integer> allowedPorts) {
            this.allowedPorts = allowedPorts;
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

    /**
     * Global AI provider config. Workspace policy still gates enablement.
     * Default provider is the deterministic mock/local adapter.
     */
    public static class Ai {
        private String defaultProvider = "mock-local";
        private int timeoutMs = 15_000;
        private int maxRetries = 2;
        private boolean logDocumentContent = false;
        private final Ollama ollama = new Ollama();
        private final External external = new External();

        public String getDefaultProvider() {
            return defaultProvider;
        }

        public void setDefaultProvider(String defaultProvider) {
            this.defaultProvider = defaultProvider;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isLogDocumentContent() {
            return logDocumentContent;
        }

        public void setLogDocumentContent(boolean logDocumentContent) {
            this.logDocumentContent = logDocumentContent;
        }

        public Ollama getOllama() {
            return ollama;
        }

        public External getExternal() {
            return external;
        }

        public static class Ollama {
            private boolean enabled = false;
            private String baseUrl = "http://127.0.0.1:11434";
            private String model = "llama3.2";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }

        public static class External {
            private boolean enabled = false;
            private String baseUrl = "";
            private String apiKey = "";
            private String model = "";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }
    }
}
