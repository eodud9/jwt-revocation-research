package com.daeyeong.jwtrevocation.revocation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisRevocationStore {

    private final String KEY_PREFIX = "revoked:";
    private final StringRedisTemplate stringRedisTemplate;

    public void save(String jti, Duration ttl){
        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + jti,
                "1",
                ttl
        );
    }

    public boolean isRevoked(String jti){
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + jti));
    }

}
