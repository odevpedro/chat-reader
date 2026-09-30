package com.example.chatreader.importer.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Leitura de datas que aparecem em exports reais. Cada adapter traduz o campo cru do
 * formato para {@link Instant} por aqui, para que a mesma data vire o mesmo instante
 * em qualquer adapter — a idempotencia (ADR-004) depende disso.
 *
 * <p>Vive no dominio pelo mesmo motivo do {@link RoleMapper}: e' traducao pura, sem
 * Spring nem Jackson, e e' usada pelos adapters. O {@code domain} nao conhece formato
 * de exportador; ele so conhece "esta data vira este instante".
 *
 * <p>Formatos aceitos, em ordem de tentativa:
 * <ol>
 *   <li>epoch em segundos, fracionario ou nao (o ChatGPT exporta {@code 1699999999.0});
 *   <li>ISO-8601 instant ({@code 2026-09-20T14:00:00Z});
 *   <li>data local ({@code 2026-09-20}) e data-hora local ({@code 2026-09-20 14:00:00}
 *       ou {@code 2026-09-20T14:00:00}), sempre em UTC.
 * </ol>
 *
 * <p>Data ausente ou incompativel devolve {@code null}: quem chama decide o padrao
 * (o chat usa {@code importedAt}). Data ilegivel nunca derruba o lote.
 */
public final class DateTimes {

    /** Epoch em segundos abaixo disso e' data, nao timestamp. */
    private static final long EPOCH_FLOOR = 100_000_000L;

    private DateTimes() {
    }

    public static Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        var value = raw.strip().replace("\"", "");
        if (value.isEmpty()) {
            return null;
        }

        if (isDigits(value)) {
            return fromEpoch(Double.parseDouble(value));
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // segue para data local
        }

        // "2026-09-20 14:00:00" e "2026-09-20T14:00:00" sem zona: assume UTC
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // segue para data pura
        }

        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static Instant fromEpoch(double epochSeconds) {
        return Instant.ofEpochMilli((long) (epochSeconds * 1000));
    }

    private static boolean isDigits(String value) {
        if (value.length() < 9 || value.length() > 20) {
            return false;
        }
        var digits = value.indexOf('.') < 0 ? value : value.substring(0, value.indexOf('.'));
        return digits.chars().allMatch(Character::isDigit) && Long.parseLong(digits) > EPOCH_FLOOR;
    }
}
