package com.pg.myutils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

/**
 * JWT（JSON Web Token）工具类
 * 用于生成和验证用户登录令牌
 */
@Component  // 让 Spring 管理这个 Bean，以便在其他地方注入
public class JwtUtils {

    // 从配置文件读取固定的签名密钥，避免每次重启都变化导致旧 token 失效
    // 通过 @PostConstruct 延迟初始化，确保 @Value 注入完成后再构建 Key
    private Key key;

    @Value("${jwt.secret}")
    private String secret;

    @javax.annotation.PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(secret));
    }

    // 从配置文件读取 token 过期时间（毫秒）
    @Value("${jwt.expire}")
    private long expire;

    /**
     * 根据用户ID和角色生成 JWT Token
     * @param uid  用户唯一标识
     * @param role 用户角色（管理员 / 普通用户）
     * @return     生成的 token 字符串
     */
    public String generateToken(String uid, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expire);
        return Jwts.builder()
                .setSubject(uid)                     // 将用户ID存入 subject
                .claim("role", role)                 // 将角色作为自定义属性存入
                .setIssuedAt(now)                    // 签发时间
                .setExpiration(expiryDate)           // 过期时间
                .signWith(key, SignatureAlgorithm.HS256) // 使用 HS256 算法签名
                .compact();                          // 生成紧凑字符串
    }

    /**
     * 解析 Token，获取其中的 Claims（声明）
     * @param token 客户端传来的 token
     * @return      解析成功返回 Claims，失败返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)               // 设置签名密钥
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;                             // 解析失败（过期、篡改等）返回 null
        }
    }

    /**
     * 从 Token 中获取用户ID
     */
    public String getUidFromToken(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * 从 Token 中获取用户角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims != null ? (String) claims.get("role") : null;
    }

    /**
     * 验证 Token 是否有效（未过期且签名正确）
     */
    public boolean validateToken(String token) {
        return parseToken(token) != null;
    }
}