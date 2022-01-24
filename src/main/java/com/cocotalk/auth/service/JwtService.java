package com.cocotalk.auth.service;

import com.cocotalk.auth.repository.UserRepository;
import com.cocotalk.auth.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final UserRepository userRepository;

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.token.exp.access}")
    public long accessTokenExp;

    @Value("${jwt.token.exp.refresh}")
    private long refreshTokenExp;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String createAccessToken(String userCid) {
        Date now = new Date();
        Claims claims = Jwts.claims()
                .setSubject("access_token")
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + accessTokenExp * 1000));
        claims.put("userCid", userCid);

        return Jwts.builder()
                .setClaims(claims)
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
    }

    public String createRefreshToken(String userCid) {
        Date now = new Date();
        Claims claims = Jwts.claims()
                .setSubject("refresh_token")
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + refreshTokenExp * 1000));
        claims.put("userCid", userCid);

        String refreshToken = Jwts.builder()
                .setClaims(claims)
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
        User user = userRepository.findByCid(userCid).orElse(null);
        if(user==null) return null;

        return refreshToken;
    }

    public String getAccessToken() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        return request.getHeader("X-ACCESS-TOKEN");
    }

    public String getRefreshToken() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        return request.getHeader("X-REFRESH-TOKEN");
    }

    public String getUserCid() {
        String accessToken = getAccessToken();
        if (accessToken == null)
            return null;
        try {
            Jws<Claims> claims = Jwts.parser().setSigningKey(secretKey).parseClaimsJws(accessToken);
            String userCid = claims.getBody().get("userCid", String.class);
            if (StringUtils.isEmpty(userCid))
                return null;
            User user = userRepository.findByCid(userCid).orElse(null);
            if (user == null)
                return null;
            return userCid;
        } catch (Exception exception) {
            return null;
        }
    }

    public User getUser() {
        String accessToken = getAccessToken();
        if (accessToken == null)
            return null;
        try {
            Jws<Claims> claims = Jwts.parser().setSigningKey(secretKey).parseClaimsJws(accessToken);
            String userCid = claims.getBody().get("userCid", String.class);
            if (StringUtils.isEmpty(userCid))
                return null;
            User userDB = userRepository.findByCid(userCid).orElse(null);
            if (userDB == null)
                return null;
            return userDB;
        } catch (Exception exception) {
            return null;
        }
    }

    public Jws<Claims> getClaims(String jwtToken) {
        try {
            return Jwts.parser().setSigningKey(secretKey).parseClaimsJws(jwtToken);
        } catch (SignatureException ex) {
            log.error("Invalid JWT signature");
            throw ex;
        } catch (MalformedJwtException ex) {
            log.error("Invalid JWT token");
            throw ex;
        }
         catch (ExpiredJwtException ex) {
            log.error("Expired JWT token");
            throw ex;
         }
        catch (UnsupportedJwtException ex) {
            log.error("Unsupported JWT token");
            throw ex;
        } catch (IllegalArgumentException ex) {
            log.error("JWT claims string is empty.");
            throw ex;
        }
    }

}