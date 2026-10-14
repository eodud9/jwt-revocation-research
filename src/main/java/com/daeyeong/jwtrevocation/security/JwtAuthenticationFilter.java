package com.daeyeong.jwtrevocation.security;

import com.daeyeong.jwtrevocation.revocation.strategy.RevocationStrategy;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final RevocationStrategy revocationStrategy;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain
                                    ) throws ServletException, IOException {

        String authentication = request.getHeader("Authorization");

        if(authentication != null && authentication.startsWith("Bearer ")){

            try{

                String token = authentication.substring(7);

                Claims claims = jwtProvider.parseClaims(token);
                String userId = claims.getSubject();
                String jti = claims.getId();

                if(revocationStrategy.isRevoked(jti)){
                    filterChain.doFilter(request, response);
                    return;
                }

                UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        Collections.emptyList()
                );

                SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);

            } catch(Exception ignored){
                // 유효하지 않은 JWT는 인증 정보를 등록하지 않는다.
            }


        }

        filterChain.doFilter(request, response);

    }
}
