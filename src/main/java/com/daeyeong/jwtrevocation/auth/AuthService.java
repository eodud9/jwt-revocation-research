package com.daeyeong.jwtrevocation.auth;

import com.daeyeong.jwtrevocation.auth.dto.LoginRequest;
import com.daeyeong.jwtrevocation.auth.dto.TokenResponse;
import com.daeyeong.jwtrevocation.revocation.RedisRevocationStore;
import com.daeyeong.jwtrevocation.revocation.RevokedToken;
import com.daeyeong.jwtrevocation.revocation.RevokedTokenRepository;
import com.daeyeong.jwtrevocation.security.JwtProvider;
import com.daeyeong.jwtrevocation.user.User;
import com.daeyeong.jwtrevocation.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RedisRevocationStore redisRevocationStore;

    public User authenticate(LoginRequest request){

        User user = userRepository.findByEmail(request.email()).orElseThrow(
                () -> new IllegalArgumentException("Invalid email or password")
        );

        if(!passwordEncoder.matches(request.password(), user.getPassword())){
            throw new IllegalArgumentException("Invalid email or password");
        }

        return user;

    }

    public TokenResponse login(LoginRequest request){

        User user = authenticate(request);

        String accessToken = jwtProvider.generateAccessToken(user.getId());

        return new TokenResponse(accessToken);

    }

    public void logout(String token){

        Claims claims = jwtProvider.parseClaims(token);
        String jti = claims.getId();

        Instant expiresAt = claims.getExpiration().toInstant();

        Instant revokedAt = Instant.now();

        RevokedToken revokedToken = new RevokedToken(jti, expiresAt, revokedAt);

        if (revokedTokenRepository.existsByJti(jti)) {
            return;
        }

        revokedTokenRepository.save(revokedToken);

        Duration ttl = Duration.between(
                Instant.now(), expiresAt
        );

        if(!ttl.isNegative() && !ttl.isZero()){
            redisRevocationStore.save(jti, ttl);
        }
    }
}
