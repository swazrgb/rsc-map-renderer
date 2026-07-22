package openrsc.maprender.bake;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared Jackson mapper for the bake's JSON writers. {@link ObjectMapper} is
 * thread-safe once built but relatively expensive to construct (it builds and
 * caches serializers on first use), so the bakers share one instance rather than
 * paying that cost — and re-warming the serializer cache — per output file.
 */
final class BakeJson {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private BakeJson() {}
}
