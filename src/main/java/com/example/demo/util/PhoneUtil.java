package com.example.demo.util;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Numéros malgaches : on retire +261 / 00261 / 261 et on garde le format local à 10 chiffres (ex. 0340000000). */
public final class PhoneUtil {

    private static final Pattern LOCAL = Pattern.compile("^0[23]\\d{8}$");

    private PhoneUtil() {}

    public static String normalize(String raw) {
        if (raw == null) {
            throw invalid();
        }
        String n = raw.replaceAll("[\\s.\\-()]", "");
        String rest = null;
        if (n.startsWith("+261")) {
            rest = n.substring(4);
        } else if (n.startsWith("00261")) {
            rest = n.substring(5);
        } else if (n.startsWith("261") && n.length() == 12) {
            rest = n.substring(3);
        }
        if (rest != null) {
            n = rest.startsWith("0") ? rest : "0" + rest;
        }
        if (!LOCAL.matcher(n).matches()) {
            throw invalid();
        }
        return n;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Numéro malgache invalide (format attendu : 034 00 000 00).");
    }
}