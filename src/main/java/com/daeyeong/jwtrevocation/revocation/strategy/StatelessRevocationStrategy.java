package com.daeyeong.jwtrevocation.revocation.strategy;


import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "jwt.revocation",
        name = "strategy",
        havingValue = "stateless"
)
public class StatelessRevocationStrategy implements RevocationStrategy {

    @Override
    public boolean isRevoked(String jti){
        return false;
    }

}
