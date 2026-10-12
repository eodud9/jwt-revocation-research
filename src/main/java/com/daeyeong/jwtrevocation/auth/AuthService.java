package com.daeyeong.jwtrevocation.auth;

import com.daeyeong.jwtrevocation.auth.dto.LoginRequest;
import com.daeyeong.jwtrevocation.auth.dto.TokenResponse;
import com.daeyeong.jwtrevocation.security.JwtProvider;
import com.daeyeong.jwtrevocation.user.User;
import com.daeyeong.jwtrevocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

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
}
