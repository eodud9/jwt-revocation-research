package com.daeyeong.jwtrevocation.revocation.metrics;

import com.daeyeong.jwtrevocation.revocation.metrics.dto.RevocationMetricsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiment/metrics")
@RequiredArgsConstructor
public class RevocationMetricsController {

    private final RevocationMetrics revocationMetrics;

    @PostMapping("/reset")
    public void reset(){
        revocationMetrics.reset();
    }

    @GetMapping
    public RevocationMetricsResponse dbCount(){
            return new RevocationMetricsResponse(revocationMetrics.getDbQueryCount());
    }

}
