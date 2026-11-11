package openrsc.livemap.dto;

/** A projectile in flight from one mob to another, each by server index. */
public record ProjectileDto(int sprite, int from, boolean fromNpc, int to, boolean toPlayer) {}
