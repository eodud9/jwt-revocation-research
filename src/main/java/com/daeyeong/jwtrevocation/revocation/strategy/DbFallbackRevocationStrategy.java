package com.daeyeong.jwtrevocation.revocation.strategy;

import com.daeyeong.jwtrevocation.revocation.RedisRevocationStore;
import com.daeyeong.jwtrevocation.revocation.RevokedTokenRepository;
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

    @Override
    public boolean isRevoked(String jti) {
        try {
            return redisRevocationStore.isRevoked(jti);
        } catch (DataAccessException e){
            return revokedTokenRepository.existsByJti(jti);
        }
    }
}
