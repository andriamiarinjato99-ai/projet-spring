package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class CashPointDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String signature;
        private String idCarte;
        private Integer idDistributeur;
        private String nom;
        private String email;
        private String numero;
        private String password;
        private Boolean validation;
        private Integer idFokontany;
        private String repere;
        private Integer marquage;
        private Double latitude;
        private String lieu;
        private Double longitude;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private String signature;
        private String idCarte;
        private Integer idDistributeur;
        private String nom;
        private String email;
        private String numero;
        private Boolean validation;
        private Integer idFokontany;
        private String repere;
        private Integer marquage;
        private Double latitude;
        private String lieu;
        private Double longitude;

    }
}