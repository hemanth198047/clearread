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
            filter.doFilter(request(), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request(), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/capabilities");
        req.setRemoteAddr("203.0.113.9");
        return req;
    }
}
