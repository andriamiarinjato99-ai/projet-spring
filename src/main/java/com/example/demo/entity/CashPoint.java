package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cash_point")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
  
    @Column(name = "id", nullable = false)
    private Integer id; 

    @Column(name = "signature", length = 5, nullable = false)
    private String signature = "EQ-"; 

    @Column(name = "id_carte", length = 250, nullable = false)
    private String idCarte; 

    @Column(name = "id_distributeur", nullable = false)
    private Integer idDistributeur; 

    @Column(name = "nom", length = 150, nullable = false)
    private String nom;

    @Column(name = "email", length = 150, nullable = false)
    private String email; 

    @Column(name = "numero", length = 20, nullable = false)
    private String numero;

    @Column(name = "password", length = 250, nullable = false)
    private String password; 

    @Column(name = "validation", columnDefinition = "BOOLEAN")
    private Boolean validation; 

    @Column(name = "id_fokontany")
    private Integer idFokontany; 

    @Column(name = "repere", length = 255)
    private String repere; 

    @Column(name = "marquage")
    private Integer marquage = 0;

    @Column(name = "latitude")
    private Double latitude; 

    @Column(name = "lieu", length = 255)
    private String lieu; 

    @Column(name = "longitude")
    private Double longitude; 
}