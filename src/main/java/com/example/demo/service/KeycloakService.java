package com.example.demo.service;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.dto.AuthDto;

/**
 * Dialogue avec Keycloak :
 *  - login / refresh : renvoie un access token propre à chaque utilisateur
 *  - createUser / deleteUser : gestion des comptes via l'API Admin (service account du client)
 */
@Service
public class KeycloakService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient rest = RestClient.create();

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    private String tokenUrl() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    private String usersUrl() {
        return serverUrl + "/admin/realms/" + realm + "/users";
    }

    private MultiValueMap<String, String> baseForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return form;
    }


    public AuthDto.TokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = baseForm("password");
        form.add("username", username);
        form.add("password", password);
        return requestUserToken(form, "Numéro ou mot de passe incorrect.");
    }

    public AuthDto.TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = baseForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestUserToken(form, "Refresh token invalide ou expiré.");
    }

    private AuthDto.TokenResponse requestUserToken(MultiValueMap<String, String> form, String errorMessage) {
        try {
            Map<String, Object> body = rest.post()
                    .uri(tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(MAP_TYPE);
            return new AuthDto.TokenResponse(
                    str(body, "access_token"),
                    str(body, "refresh_token"),
                    num(body, "expires_in"),
                    num(body, "refresh_expires_in"),
                    str(body, "token_type"));
        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            boolean badCredentials = body != null && body.contains("Invalid user credentials");
            // Si ce n'est pas un simple mauvais mot de passe, on affiche la raison donnée par Keycloak
            String detail = badCredentials ? "" : " Détail Keycloak : " + abbreviate(body);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, errorMessage + detail);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est injoignable.");
        }
    }

    public String createUser(String numero, String email, String password, String nom) {
        String adminToken = serviceAccountToken();
        Map<String, Object> user = Map.of(
                "username", numero,
                "email", email,
                "firstName", "CashPoint",
                "lastName", nom,
                "enabled", true,
                "emailVerified", true,
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        try {
            ResponseEntity<Void> response = rest.post()
                    .uri(usersUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();
            URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Réponse Keycloak inattendue.");
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce numéro ou cet email existe déjà dans Keycloak.");
        } catch (HttpClientErrorException.BadRequest e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Keycloak a refusé les données (mot de passe trop faible ou champ invalide).");
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Création Keycloak refusée (" + e.getStatusCode().value() + "). Vérifiez les rôles du service account.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est injoignable.");
        }
    }


    public void deleteUser(String keycloakUserId) {
        try {
            rest.delete()
                    .uri(usersUrl() + "/" + keycloakUserId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceAccountToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            
        }
    }

    /** Supprime le compte Keycloak à partir du username (= numéro). Sans erreur si le compte n'existe pas. */
    public void deleteUserByUsername(String numero) {
        String adminToken = serviceAccountToken();
        try {
            List<Map<String, Object>> users = rest.get()
                    .uri(usersUrl() + "?username={u}&exact=true", numero)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (users == null || users.isEmpty() || users.get(0).get("id") == null) {
                return;
            }
            String id = users.get(0).get("id").toString();
            rest.delete()
                    .uri(usersUrl() + "/" + id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Suppression Keycloak refusée (" + e.getStatusCode().value()
                    + "). Vérifiez que le service account a le rôle manage-users.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est injoignable.");
        }
    }

    /** Remplace le mot de passe Keycloak d'un compte (username = numéro). Appelé seulement après vérification locale. */
    public void resetPassword(String numero, String newPassword) {
        String adminToken = serviceAccountToken();
        try {
            List<Map<String, Object>> users = rest.get()
                    .uri(usersUrl() + "?username={u}&exact=true", numero)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (users == null || users.isEmpty() || users.get(0).get("id") == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Compte introuvable dans Keycloak.");
            }
            String id = users.get(0).get("id").toString();
            rest.put()
                    .uri(usersUrl() + "/" + id + "/reset-password")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", newPassword, "temporary", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Synchronisation du mot de passe Keycloak refusée (" + e.getStatusCode().value()
                    + "). Vérifiez que le service account a le rôle manage-users.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est injoignable.");
        }
    }

    private String serviceAccountToken() {
        try {
            Map<String, Object> body = rest.post()
                    .uri(tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(baseForm("client_credentials"))
                    .retrieve()
                    .body(MAP_TYPE);
            return str(body, "access_token");
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Keycloak a refusé le client '" + clientId + "' du realm '" + realm + "' (HTTP "
                    + e.getStatusCode().value() + ") : " + abbreviate(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est injoignable.");
        }
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) return "(réponse vide)";
        return text.length() > 250 ? text.substring(0, 250) + "..." : text;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : v.toString();
    }

    private static Long num(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.longValue() : null;
    }
}