package inst.iop.LibraryManager.authentication.services;

import inst.iop.LibraryManager.authentication.dtos.LoginDto;
import inst.iop.LibraryManager.authentication.dtos.RegisterDto;
import inst.iop.LibraryManager.authentication.entities.JwtToken;
import inst.iop.LibraryManager.authentication.entities.User;
import inst.iop.LibraryManager.authentication.entities.enums.Role;
import inst.iop.LibraryManager.authentication.entities.enums.TokenType;
import inst.iop.LibraryManager.authentication.repositories.TokenRepository;
import inst.iop.LibraryManager.authentication.repositories.UserRepository;
import inst.iop.LibraryManager.utilities.exceptions.BadRequestDetailsException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

import static inst.iop.LibraryManager.utilities.BindingResultHandler.handleBindingResult;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

  private final UserRepository userRepository;
  private final TokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  private final EmailService emailService;

  private static final int UUID_LENGTH = 32;

  @Override
  @Transactional
  public void register(@Valid RegisterDto request, BindingResult bindingResult)
      throws BadRequestDetailsException {
    Map<String, Object> violations = handleBindingResult(bindingResult);
    if (!violations.isEmpty()) {
      throw new BadRequestDetailsException(violations);
    }

    Optional<User> u = userRepository.findUserByEmail(request.getEmail());
    if (u.isPresent()) {
      violations.put("email", "An user with the same email is already existed");
      throw new BadRequestDetailsException(violations);
    }

    String confirmationCode = generateSecureUuid();
    try {
      boolean isSendingSuccess = emailService.sendConfirmationEmailToRecipient(
          "minhdinhnguyen1495@gmail.com", request.getFirstName(), request.getLastName(), confirmationCode
      );
      if (!isSendingSuccess) {
        violations.put("email", "Unable to send confirmation email. Please check your input");
        throw new BadRequestDetailsException(violations);
      }
    } catch (IOException e) {
      violations.put("email", "Unable to send confirmation email");
      throw new BadRequestDetailsException(violations);
    }

    User user = User.builder()
        .email(request.getEmail())
        .password(passwordEncoder.encode(request.getPassword()))
        .firstName(request.getFirstName())
        .lastName(request.getLastName())
        .role(Role.USER)
        .borrowEntries(new HashSet<>())
        .created(LocalDateTime.now())
        .enabled(false)
        .confirmationCode(confirmationCode)
        .build();
    userRepository.save(user);
  }

  String generateSecureUuid() {
    SecureRandom secureRandom = new SecureRandom();
    byte[] randomBytes = new byte[UUID_LENGTH / 2];

    secureRandom.nextBytes(randomBytes);

    StringBuilder sb = new StringBuilder(UUID_LENGTH);
    for (byte b : randomBytes) {
      sb.append(String.format("%02x", b));
    }

    return sb.toString();
  }

  @Override
  @Transactional
  public Map<String, Object> login(LoginDto request) throws BadRequestDetailsException {
    try {
      authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
      );
      User user = userRepository.findUserByEmail(request.getEmail()).orElseThrow();
      String accessToken = jwtService.generateToken(user);
      String refreshToken = jwtService.generateRefreshToken(user);
      revokeAllTokens(user);
      saveToken(user, accessToken);

      Map<String, Object> details = new HashMap<>();
      details.put("accessToken", accessToken);
      details.put("refreshToken", refreshToken);
      return details;
    } catch (AuthenticationException | NoSuchElementException e) {
      Map<String, Object> violations = new HashMap<>();
      violations.put("authentication", "Unable to authenticate with provided email and password. " +
          "Please check your inputs or if you have confirmed your account");
      throw new BadRequestDetailsException(violations);
    }
  }

  @Override
  @Transactional
  public Map<String, Object> refreshToken(HttpServletRequest request, HttpServletResponse response)
      throws BadRequestDetailsException {
    String authenticationHeader = request.getHeader("Authorization");
    if (authenticationHeader == null || !authenticationHeader.startsWith("Bearer ")) {
      throw new MalformedJwtException("Invalid header format for refreshing JWT");
    }
    String refreshToken = authenticationHeader.substring(7);
    String email = jwtService.extractUsername(refreshToken);
    User user = userRepository.findUserByEmail(email).orElseThrow(
        () -> new MalformedJwtException("Unable to extract username from token")
    );
    Optional<JwtToken> token = tokenRepository.findTokenByString(refreshToken);
    if (token.isEmpty() || token.get().isRevoked() || token.get().isExpired()) {
      throw new MalformedJwtException("Unable to refresh using provided token");
    }

    if (jwtService.isTokenValid(refreshToken, user)) {
      var accessToken = jwtService.generateToken(user);
      revokeAllTokens(user);
      saveToken(user, accessToken);
      Map<String, Object> details = new HashMap<>();
      details.put("accessToken", accessToken);
      details.put("refreshToken", refreshToken);
      return details;
    }

    throw new MalformedJwtException("Unable to refresh using provided token");
  }

  @Override
  @Transactional
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    String authenticationHeader = request.getHeader("Authorization");
    if (authenticationHeader == null || !authenticationHeader.startsWith("Bearer ")) {
      return;
    }

    String token = authenticationHeader.substring(7);
    Optional<JwtToken> storedToken = tokenRepository.findTokenByString(token);
    if (storedToken.isPresent()) {
      storedToken.get().setExpired(true);
      storedToken.get().setRevoked(true);
      tokenRepository.save(storedToken.get());
    }
    SecurityContextHolder.clearContext();
  }

  @Override
  public void confirmRegister(String email, String confirmationCode) {
    Optional<User> u = userRepository.findUserByEmail(email);
    if (u.isEmpty()) {
      Map<String, Object> violation = new HashMap<>();
      violation.put("url", "Invalid confirmation link");
      throw new BadRequestDetailsException(violation);
    }

    User user = u.get();
    if (user.getConfirmationCode() != null && user.getConfirmationCode().equals(confirmationCode)
        && !user.isEnabled()) {
      user.setEnabled(true);
      userRepository.save(user);
    } else {
      Map<String, Object> violation = new HashMap<>();
      violation.put("url", "The code is invalid or already confirmed");
      throw new BadRequestDetailsException(violation);
    }
  }

  @Override
  public void saveToken(User user, String jwtToken) {
    JwtToken token = JwtToken.builder()
        .user(user)
        .token(jwtToken)
        .tokenType(TokenType.BEARER)
        .expired(false)
        .revoked(false)
        .build();
    tokenRepository.save(token);
  }

  private void revokeAllTokens(User user) {
    var storedTokens = tokenRepository.findAllValidTokensByUserId(user.getId());
    if (!storedTokens.isEmpty()) {
      storedTokens.forEach(t -> {
        t.setExpired(true);
        t.setRevoked(true);
      });
      tokenRepository.saveAll(storedTokens);
    }
  }
}
