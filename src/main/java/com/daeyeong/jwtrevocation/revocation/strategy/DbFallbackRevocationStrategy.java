package com.daeyeong.jwtrevocation.revocation.strategy;

import com.daeyeong.jwtrevocation.revocation.RedisRevocationStore;
import com.daeyeong.jwtrevocation.revocation.RevokedTokenRepository;
import com.daeyeong.jwtrevocation.revocation.metrics.RevocationMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "jwt.revocation",
        name = "strategy",
        havingValue = "db-fallback"
)
@RequiredArgsConstructor
public class DbFallbackRevocationStrategy implements RevocationStrategy{

    private final RedisRevocationStore redisRevocationStore;
    private final RevokedTokenRepository revokedTokenRepository;
    private final RevocationMetrics revocationMetrics;

    @Override
    public boolean isRevoked(String jti) {
        try {
            return redisRevocationStore.isRevoked(jti);
        } catch (DataAccessException e){
            revocationMetrics.incrementDbQueryCount();
            return revokedTokenRepository.existsByJti(jti);
        }
    }
}
