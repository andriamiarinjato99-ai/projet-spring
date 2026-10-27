package com.example.demo.repository;

import com.example.demo.entity.CashPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CashPointRepository extends JpaRepository<CashPoint, Integer> {
    Optional<CashPoint> findByEmail(String email);
    Optional<CashPoint> findByNumero(String numero);
    Optional<CashPoint> findByNom(String nom);
    java.util.List<CashPoint> findByIdDistributeur(Integer idDistributeur);
    java.util.List<CashPoint> findByIdFokontany(Integer idFokontany);
}