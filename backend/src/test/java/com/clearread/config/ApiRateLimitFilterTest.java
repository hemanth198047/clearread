package com.clearread.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTest {

    @Test
    void enforcesPerIpLimitForUnauthenticatedMutations() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(0);
        props.getLimits().setPublicApiRequestsPerMinute(3);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/v1/preview/x"), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/v1/preview/x"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void authenticatedMutationsUseApiLimit() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(2);
        props.getLimits().setPublicApiRequestsPerMinute(100);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(authed("POST", "/v1/documents/upload"), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(authed("POST", "/v1/documents/upload"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void zeroApiLimitDisablesAuthenticatedEnforcement() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(0);
        props.getLimits().setPublicApiRequestsPerMinute(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(authed("POST", "/v1/documents/upload"), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void bootstrapGetEndpointsAreNotRateLimited() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(1);
        props.getLimits().setPublicApiRequestsPerMinute(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        MockHttpServletResponse capsRes = new MockHttpServletResponse();
        MockFilterChain capsChain = new MockFilterChain();
        filter.doFilter(request("GET", "/v1/capabilities"), capsRes, capsChain);
        assertThat(capsChain.getRequest()).isNotNull();
        assertThat(capsRes.getStatus()).isNotEqualTo(429);

        MockHttpServletResponse brandingRes = new MockHttpServletResponse();
        MockFilterChain brandingChain = new MockFilterChain();
        filter.doFilter(request("GET", "/v1/branding/default"), brandingRes, brandingChain);
        assertThat(brandingChain.getRequest()).isNotNull();
        assertThat(brandingRes.getStatus()).isNotEqualTo(429);
    }

    @Test
    void authenticatedDocumentReadsAreExempt() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(1);
        props.getLimits().setPublicApiRequestsPerMinute(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        // Burst of Library / document open / cleardoc GETs must never 429 when Bearer is present.
        String[] paths = {
                "/v1/library",
                "/v1/documents",
                "/v1/documents/0a6845f3-ba2a-4d88-8473-71da2af76a54",
                "/v1/documents/0a6845f3-ba2a-4d88-8473-71da2af76a54/cleardoc",
                "/v1/documents/0a6845f3-ba2a-4d88-8473-71da2af76a54/original",
                "/v1/search"
        };
        for (int round = 0; round < 5; round++) {
            for (String path : paths) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                MockFilterChain chain = new MockFilterChain();
                filter.doFilter(authed("GET", path), res, chain);
                assertThat(chain.getRequest()).as(path).isNotNull();
                assertThat(res.getStatus()).as(path).isNotEqualTo(429);
            }
        }

        // Unauthenticated public traffic still counts toward the public limit.
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/v1/shared/abc"), ok, new MockFilterChain());
        assertThat(ok.getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/v1/shared/abc"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void isAuthenticatedReadPathRecognizesLibraryAndDocuments() {
        assertThat(ApiRateLimitFilter.isAuthenticatedReadPath("/v1/library")).isTrue();
        assertThat(ApiRateLimitFilter.isAuthenticatedReadPath("/v1/documents")).isTrue();
        assertThat(ApiRateLimitFilter.isAuthenticatedReadPath("/v1/documents/abc/cleardoc")).isTrue();
        assertThat(ApiRateLimitFilter.isAuthenticatedReadPath("/v1/search")).isTrue();
        assertThat(ApiRateLimitFilter.isAuthenticatedReadPath("/v1/preview/tok")).isFalse();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr("203.0.113.9");
        return req;
    }

    private static MockHttpServletRequest authed(String method, String path) {
        MockHttpServletRequest req = request(method, path);
        req.addHeader("Authorization", "Bearer test-token");
        return req;
    }
}
