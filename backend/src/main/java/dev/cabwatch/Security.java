package dev.cabwatch;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Single-operator demo. Short-lived bearer tokens avoid hashing a password per reading. */
@Configuration
public class Security {
  @Bean
  SecretKeySpec signingKey(@Value("${cabwatch.operator-password}") String password)
      throws Exception {
    if (password.length() < 16)
      throw new IllegalArgumentException("OPERATOR_PASSWORD must be at least 16 characters");
    return new SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)),
        "HmacSHA256");
  }

  @Bean
  JwtDecoder decoder(SecretKeySpec key) {
    var d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(JwtValidators.createDefaultWithIssuer("cabwatch-java"));
    return d;
  }

  @Bean
  JwtEncoder encoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity h) throws Exception {
    return h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth")
                    .permitAll()
                    .requestMatchers("/api/**", "/actuator/prometheus")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            r -> r.jwt(org.springframework.security.config.Customizer.withDefaults()))
        .build();
  }

  @RestController
  public static class Login {
    private final JwtEncoder encoder;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final String hash;
    private long window;
    private int attempts;

    public Login(JwtEncoder encoder, @Value("${cabwatch.operator-password}") String password) {
      this.encoder = encoder;
      hash = passwords.encode(password);
    }

    public record Credentials(String password) {}

    @PostMapping("/api/auth")
    public synchronized Map<String, String> login(@RequestBody Credentials c) {
      long minute = System.currentTimeMillis() / 60000;
      if (minute != window) {
        window = minute;
        attempts = 0;
      }
      if (++attempts > 30)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again in one minute");
      if (c.password() == null || !passwords.matches(c.password(), hash))
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password");
      var now = Instant.now();
      var claims =
          JwtClaimsSet.builder()
              .issuer("cabwatch-java")
              .subject("operator")
              .issuedAt(now)
              .expiresAt(now.plusSeconds(28800))
              .build();
      return Map.of(
          "token",
          encoder
              .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
              .getTokenValue());
    }
  }
}
