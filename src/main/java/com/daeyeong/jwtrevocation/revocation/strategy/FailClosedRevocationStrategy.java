package com.daeyeong.jwtrevocation.revocation.strategy;

import com.daeyeong.jwtrevocation.revocation.RedisRevocationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "jwt.revocation",
        name = "strategy",
        havingValue = "fail-closed"
)
public class FailClosedRevocationStrategy implements RevocationStrategy{

    private final RedisRevocationStore redisRevocationStore;

    @Override
    public boolean isRevoked(String jti){

        try {

            return redisRevocationStore.isRevoked(jti);

        } catch (DataAccessException e){

            return true;

        }

    }
}
