package com.example.demo.service;

import com.example.demo.dto.AuthDto;
import com.example.demo.dto.CashPointDto;
import com.example.demo.entity.CashPoint;
import com.example.demo.repository.CashPointRepository;
import com.example.demo.util.PhoneUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CashPointService {

    private static final Logger log = LoggerFactory.getLogger(CashPointService.class);

    private final CashPointRepository cashPointRepository;
    private final KeycloakService keycloakService;

    /** Dev uniquement : accepte aussi le hash SHA-256 stocké en base comme mot de passe de login. */
    @Value("${app.auth.allow-hash-login:false}")
    private boolean allowHashLogin;

    public CashPointService(CashPointRepository cashPointRepository, KeycloakService keycloakService) {
        this.cashPointRepository = cashPointRepository;
        this.keycloakService = keycloakService;
    }

    // Creation / Inscription 
    public CashPointDto.Response createCashPoint(CashPointDto.Request request) {
        validate(request);
        request.setNumero(PhoneUtil.normalize(request.getNumero())); // ex. +261 34 00 000 00 -> 0340000000
        if (cashPointRepository.findByNumero(request.getNumero()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce numéro est déjà utilisé.");
        }
        if (cashPointRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet email est déjà utilisé.");
        }

        // Le mot de passe est haché UNE fois : le même hash est stocké en base ET sert de mot de passe Keycloak.
        // Ainsi le login marche aussi bien avec le mot de passe normal qu'avec le hash (voir login()).
        String hashedPassword = sha256(request.getPassword());

        // 1) Le compte est d'abord créé dans Keycloak : c'est lui qui délivre les access tokens
        String keycloakUserId = keycloakService.createUser(
                request.getNumero(), request.getEmail(), hashedPassword, request.getNom());

        // 2) Puis enregistré en base ; si ça échoue, on annule côté Keycloak
        try {
            return saveCashPoint(request, hashedPassword);
        } catch (RuntimeException e) {
            keycloakService.deleteUser(keycloakUserId);
            throw e;
        }
    }

    private void validate(CashPointDto.Request r) {
        List<String> missing = new ArrayList<>();
        if (isBlank(r.getEmail())) missing.add("email");
        if (isBlank(r.getPassword())) missing.add("password");
        if (isBlank(r.getNom())) missing.add("nom");
        if (isBlank(r.getNumero())) missing.add("numero");
        if (isBlank(r.getIdCarte())) missing.add("idCarte");
        if (r.getIdDistributeur() == null) missing.add("idDistributeur");
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Champs obligatoires manquants : " + String.join(", ", missing));
        }
        if (!r.getEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Format d'email invalide.");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private CashPointDto.Response saveCashPoint(CashPointDto.Request request, String hashedPassword) {
        CashPoint cashPoint = new CashPoint();
        if (request.getSignature() != null) {
            cashPoint.setSignature(request.getSignature()); // sinon valeur par défaut "EQ-"
        }
        cashPoint.setIdCarte(request.getIdCarte());
        cashPoint.setIdDistributeur(request.getIdDistributeur());
        cashPoint.setNom(request.getNom());
        cashPoint.setEmail(request.getEmail());
        cashPoint.setNumero(request.getNumero());
        
        cashPoint.setPassword(hashedPassword); // déjà haché (SHA-256 hex)
        
        cashPoint.setValidation(request.getValidation());
        cashPoint.setIdFokontany(request.getIdFokontany());
        cashPoint.setRepere(request.getRepere());
        cashPoint.setMarquage(request.getMarquage());
        cashPoint.setLatitude(request.getLatitude());
        cashPoint.setLieu(request.getLieu());
        cashPoint.setLongitude(request.getLongitude());

        CashPoint savedCashPoint = cashPointRepository.save(cashPoint);
        return mapToResponse(savedCashPoint);
    }

    // Authentification : on vérifie en local (SHA-256), puis Keycloak délivre l'access token de l'utilisateur
    public AuthDto.LoginResponse login(String numero, String password) {
        if (isBlank(numero) || isBlank(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Numéro et mot de passe obligatoires.");
        }
        String normalized = PhoneUtil.normalize(numero);
        CashPoint cashPoint = cashPointRepository.findByNumero(normalized)
                .orElseThrow(() -> badCredentials());
        String stored = cashPoint.getPassword();

        // 1) mot de passe normal (cas du frontend) : son SHA-256 doit égaler le hash de la base
        boolean ok = constantTimeEquals(sha256(password), stored);
        // 2) hash SHA-256 de la base envoyé tel quel (pratique pour tester dans Bruno) : désactivable par propriété
        if (!ok && allowHashLogin) {
            ok = constantTimeEquals(password, stored);
        }
        if (!ok) {
            log.warn("Login refusé pour {} : mot de passe différent de celui de la base (SHA-256)"
                    + (allowHashLogin ? " et du hash stocké." : "."), normalized);
            throw badCredentials();
        }
        // Dans Keycloak, le mot de passe du compte EST ce hash
        AuthDto.TokenResponse tokens;
        try {
            tokens = keycloakService.login(normalized, stored);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() != 401) {
                throw e;
            }
            log.warn("Keycloak a refusé {} alors que la base valide le mot de passe : synchronisation du mot de passe Keycloak.", normalized);
            keycloakService.resetPassword(normalized, stored);
            tokens = keycloakService.login(normalized, stored);
        }
        return toLoginResponse(cashPoint, tokens);
    }

    /** Profil sans le mot de passe (ni l'id), suivi des tokens. */
    private AuthDto.LoginResponse toLoginResponse(CashPoint c, AuthDto.TokenResponse t) {
        return new AuthDto.LoginResponse(
                c.getSignature(), c.getIdCarte(), c.getIdDistributeur(), c.getNom(), c.getEmail(), c.getNumero(),
                c.getValidation(), c.getIdFokontany(), c.getRepere(), c.getMarquage(), c.getLatitude(),
                c.getLieu(), c.getLongitude(),
                t.accessToken(), t.refreshToken(), t.expiresIn(), t.refreshExpiresIn(), t.tokenType());
    }

    /** SHA-256 en hexadécimal minuscule (64 caractères). */
    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseStatusException badCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Numéro ou mot de passe incorrect.");
    }

    public AuthDto.TokenResponse refresh(String refreshToken) {
        if (isBlank(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken obligatoire.");
        }
        return keycloakService.refresh(refreshToken);
    }

    public Optional<CashPointDto.Response> getCashPointByNumero(String numero) {
        return cashPointRepository.findByNumero(numero).map(this::mapToResponse);
    }


    private CashPointDto.Response mapToResponse(CashPoint cashPoint) {
        CashPointDto.Response response = new CashPointDto.Response();
        response.setId(cashPoint.getId());
        response.setSignature(cashPoint.getSignature());
        response.setIdCarte(cashPoint.getIdCarte());
        response.setIdDistributeur(cashPoint.getIdDistributeur());
        response.setNom(cashPoint.getNom());
        response.setEmail(cashPoint.getEmail());
        response.setNumero(cashPoint.getNumero());
        response.setValidation(cashPoint.getValidation());
        response.setIdFokontany(cashPoint.getIdFokontany());
        response.setRepere(cashPoint.getRepere());
        response.setMarquage(cashPoint.getMarquage());
        response.setLatitude(cashPoint.getLatitude());
        response.setLieu(cashPoint.getLieu());
        response.setLongitude(cashPoint.getLongitude());
        return response;
    }
    
    public java.util.List<CashPointDto.Response> getAllCashPoints() {
        return cashPointRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList(); 
    }
    public java.util.Optional<CashPointDto.Response> getCashPointById(Integer id) {
        return cashPointRepository.findById(id)
                .map(this::mapToResponse);
    }
   
    public CashPointDto.Response updateByNumero(String numero, CashPointDto.Request r) {
        CashPoint cp = cashPointRepository.findByNumero(numero)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
        applyProfileFields(cp, r);
        return mapToResponse(cashPointRepository.save(cp));
    }
    public CashPointDto.Response updateCashPoint(Integer id, CashPointDto.Request r) {
        CashPoint cp = cashPointRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash-point introuvable."));
        applyProfileFields(cp, r);
        if (r.getValidation() != null) cp.setValidation(r.getValidation());
        return mapToResponse(cashPointRepository.save(cp));
    }
    public void deleteCashPoint(Integer id) {
        CashPoint cp = cashPointRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash-point introuvable."));
        keycloakService.deleteUserByUsername(cp.getNumero());
        cashPointRepository.delete(cp);
    }

    private void applyProfileFields(CashPoint cp, CashPointDto.Request r) {
        if (r.getNom() != null)            cp.setNom(r.getNom());
        if (r.getIdCarte() != null)        cp.setIdCarte(r.getIdCarte());
        if (r.getIdDistributeur() != null) cp.setIdDistributeur(r.getIdDistributeur());
        if (r.getIdFokontany() != null)    cp.setIdFokontany(r.getIdFokontany());
        if (r.getRepere() != null)         cp.setRepere(r.getRepere());
        if (r.getMarquage() != null)       cp.setMarquage(r.getMarquage());
        if (r.getLatitude() != null)       cp.setLatitude(r.getLatitude());
        if (r.getLieu() != null)           cp.setLieu(r.getLieu());
        if (r.getLongitude() != null)      cp.setLongitude(r.getLongitude());
    }
}