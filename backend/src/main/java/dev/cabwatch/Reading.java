package dev.cabwatch;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record Reading(
    @NotNull UUID eventId,
    @NotNull UUID tripId,
    @Min(1) long sequence,
    @Min(1) long capturedAt,
    @DecimalMin("-500") @DecimalMax("1500") double x,
    @DecimalMin("-500") @DecimalMax("1500") double y) {}
