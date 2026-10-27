package com.example.demo.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.AuthDto;
import com.example.demo.dto.CashPointDto;
import com.example.demo.service.CashPointService;

// CORS : géré globalement dans config/SecurityConfig
@RestController
@RequestMapping("/api/cash-points")
public class CashPointController {

    private final CashPointService cashPointService;

    public CashPointController(CashPointService cashPointService) {
        this.cashPointService = cashPointService;
    }

    // ---------- Routes publiques ----------

    @PostMapping("/register")
    public ResponseEntity<CashPointDto.Response> register(@RequestBody CashPointDto.Request request) {
        return new ResponseEntity<>(cashPointService.createCashPoint(request), HttpStatus.CREATED);
    }

    /**
     * Login par numéro de téléphone malgache.
     * Corps de la réponse : le profil seulement. Les tokens sont dans les en-têtes
     * X-Access-Token, X-Refresh-Token et X-Expires-In.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthDto.ProfileResponse> login(@RequestBody LoginRequest loginRequest) {
        AuthDto.LoginResponse r = cashPointService.login(loginRequest.getNumero(), loginRequest.getPassword());

        AuthDto.ProfileResponse profile = new AuthDto.ProfileResponse(
                r.signature(), r.idCarte(), r.idDistributeur(), r.nom(), r.email(), r.numero(),
                r.validation(), r.idFokontany(), r.repere(), r.marquage(),
                r.latitude(), r.lieu(), r.longitude());

        return ResponseEntity.ok()
                .header("X-Access-Token", r.accessToken())
                .header("X-Refresh-Token", r.refreshToken())
                .header("X-Expires-In", String.valueOf(r.expiresIn()))
                .body(profile);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDto.TokenResponse> refresh(@RequestBody AuthDto.RefreshRequest request) {
        return ResponseEntity.ok(cashPointService.refresh(request.refreshToken()));
    }

    // ---------- Utilisateur connecté : son propre compte ----------

    /** Profil de l'utilisateur identifié par son token. */
    @GetMapping("/me")
    public ResponseEntity<CashPointDto.Response> me(@AuthenticationPrincipal Jwt jwt) {
        return cashPointService.getCashPointByNumero(jwt.getClaimAsString("preferred_username"))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Modifie son propre profil (numero, email, password et validation ne sont pas modifiables ici). */
    @PutMapping("/me")
    public ResponseEntity<CashPointDto.Response> updateMe(@AuthenticationPrincipal Jwt jwt,
                                                          @RequestBody CashPointDto.Request request) {
        return ResponseEntity.ok(
                cashPointService.updateByNumero(jwt.getClaimAsString("preferred_username"), request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CashPointDto.Response> getCashPointById(@PathVariable Integer id,
                                                                  @AuthenticationPrincipal Jwt jwt,
                                                                  Authentication authentication) {
        String me = jwt.getClaimAsString("preferred_username");
        boolean admin = isAdmin(authentication);
        return cashPointService.getCashPointById(id)
                .filter(r -> admin || (me != null && me.equals(r.getNumero())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
    @GetMapping
    public ResponseEntity<List<CashPointDto.Response>> getAllCashPoints() {
        return ResponseEntity.ok(cashPointService.getAllCashPoints());
    }

    @PutMapping("/{id}")
    public ResponseEntity<CashPointDto.Response> updateCashPoint(@PathVariable Integer id,
                                                                 @RequestBody CashPointDto.Request request) {
        return ResponseEntity.ok(cashPointService.updateCashPoint(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCashPoint(@PathVariable Integer id) {
        cashPointService.deleteCashPoint(id);
        return ResponseEntity.noContent().build();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
    }

    public static class LoginRequest {
        private String numero;
        private String password;

        public String getNumero() { return numero; }
        public void setNumero(String numero) { this.numero = numero; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}