package com.clearread.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTest {

    @Test
    void enforcesPerIpLimit() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(3);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(request("/v1/documents"), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("/v1/documents"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void bootstrapGetEndpointsAreNotRateLimited() throws Exception {
        ClearReadProperties props = new ClearReadProperties();
        props.getLimits().setApiRequestsPerMinute(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props);

        MockHttpServletResponse capsRes = new MockHttpServletResponse();
        MockFilterChain capsChain = new MockFilterChain();
        filter.doFilter(request("/v1/capabilities"), capsRes, capsChain);
        assertThat(capsChain.getRequest()).isNotNull();
        assertThat(capsRes.getStatus()).isNotEqualTo(429);

        MockHttpServletResponse brandingRes = new MockHttpServletResponse();
        MockFilterChain brandingChain = new MockFilterChain();
        filter.doFilter(request("/v1/branding/default"), brandingRes, brandingChain);
        assertThat(brandingChain.getRequest()).isNotNull();
        assertThat(brandingRes.getStatus()).isNotEqualTo(429);

        // Documents list still counts toward the limit.
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request("/v1/documents"), ok, new MockFilterChain());
        assertThat(ok.getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("/v1/documents"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr("203.0.113.9");
        return req;
    }
}
