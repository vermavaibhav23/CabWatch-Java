package dev.cabwatch;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Account tokens avoid password hashing or database lookups on the telemetry path. */
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
                a.requestMatchers("/api/auth", "/api/auth/login", "/api/auth/register")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasAuthority("SCOPE_operator")
                    .requestMatchers("/api/**")
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
    private final JdbcTemplate db;
    private long window;
    private int attempts;

    public Login(
        JwtEncoder encoder,
        JdbcTemplate db,
        @Value("${cabwatch.operator-password}") String password) {
      this.encoder = encoder;
      this.db = db;
      hash = passwords.encode(password);
    }

    public record Credentials(String password) {}

    public record AccountCredentials(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 72) String password) {}

    public record Signup(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 72) String password) {}

    private void throttle() {
      long minute = System.currentTimeMillis() / 60000;
      if (minute != window) {
        window = minute;
        attempts = 0;
      }
      if (++attempts > 30)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again in one minute");
    }

    private void checkPassword(String password) {
      if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes");
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Map<String, String> register(@Valid @RequestBody Signup c) {
      throttle();
      checkPassword(c.password());
      String id = UUID.randomUUID().toString();
      int inserted =
          db.update(
              "insert into accounts(id,name,email,password_hash) values (?,?,?,?) on"
                  + " conflict(email) do nothing",
              id,
              c.name().trim(),
              c.email().trim().toLowerCase(Locale.ROOT),
              passwords.encode(c.password()));
      if (inserted == 0)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
      return issue(id, c.name().trim(), false);
    }

    @PostMapping("/api/auth/login")
    public synchronized Map<String, String> accountLogin(@Valid @RequestBody AccountCredentials c) {
      throttle();
      checkPassword(c.password());
      var rows =
          db.queryForList(
              "select id,name,password_hash from accounts where email=?",
              c.email().trim().toLowerCase(Locale.ROOT));
      // Use a real BCrypt hash even for unknown accounts to avoid an obvious timing distinction.
      String candidate = rows.isEmpty() ? hash : (String) rows.get(0).get("password_hash");
      if (!passwords.matches(c.password(), candidate) || rows.isEmpty())
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
      return issue((String) rows.get(0).get("id"), (String) rows.get(0).get("name"), false);
    }

    @PostMapping("/api/auth")
    public synchronized Map<String, String> login(@RequestBody Credentials c) {
      throttle();
      checkPassword(c.password());
      if (c.password() == null || !passwords.matches(c.password(), hash))
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password");
      return issue("operator", "Operator", true);
    }

    private Map<String, String> issue(String id, String name, boolean operator) {
      var now = Instant.now();
      var claims =
          JwtClaimsSet.builder()
              .issuer("cabwatch-java")
              .subject(id)
              .claim("scope", operator ? "operator" : "fleet")
              .issuedAt(now)
              .expiresAt(now.plusSeconds(28800))
              .build();
      return Map.of(
          "name",
          name,
          "token",
          encoder
              .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
              .getTokenValue());
    }
  }
}
