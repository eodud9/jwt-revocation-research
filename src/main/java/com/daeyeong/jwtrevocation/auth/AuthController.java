package com.daeyeong.jwtrevocation.auth;

import com.daeyeong.jwtrevocation.auth.dto.LoginRequest;
import com.daeyeong.jwtrevocation.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request){
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authentication){

        String token = authentication.substring(7);

        authService.logout(token);

        return ResponseEntity.ok().build();
    }

}
