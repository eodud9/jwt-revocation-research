package com.daeyeong.jwtrevocation.revocation.strategy;

public interface RevocationStrategy {
    boolean isRevoked(String jti);
}
