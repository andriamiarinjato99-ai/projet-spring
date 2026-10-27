package com.example.demo.dto;

public class AuthDto {

    public record TokenResponse(String accessToken,
                                String refreshToken,
                                Long expiresIn,
                                Long refreshExpiresIn,
                                String tokenType) {}

    /** Réponse interne du service : le profil du cash-point (sans mot de passe) + les tokens Keycloak. */
    public record LoginResponse(String signature,
                                String idCarte,
                                Integer idDistributeur,
                                String nom,
                                String email,
                                String numero,
                                Boolean validation,
                                Integer idFokontany,
                                String repere,
                                Integer marquage,
                                Double latitude,
                                String lieu,
                                Double longitude,
                                String accessToken,
                                String refreshToken,
                                Long expiresIn,
                                Long refreshExpiresIn,
                                String tokenType) {}

    /** Ce que le client voit dans le corps de la réponse /login : le profil seulement, sans token. */
    public record ProfileResponse(String signature,
                                  String idCarte,
                                  Integer idDistributeur,
                                  String nom,
                                  String email,
                                  String numero,
                                  Boolean validation,
                                  Integer idFokontany,
                                  String repere,
                                  Integer marquage,
                                  Double latitude,
                                  String lieu,
                                  Double longitude) {}

    public record RefreshRequest(String refreshToken) {}
}