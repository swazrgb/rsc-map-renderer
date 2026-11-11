package openrsc.livemap.auth;

import java.util.Set;

/**
 * Which requests to {@code /api} need a logged-in viewer, beyond every write: reads under any of
 * {@code privilegedReads}, each a path such as {@code /api/kv} that also covers its sub-paths. None when the
 * application provides no policy.
 */
public record AuthPolicy(Set<String> privilegedReads) {

    public AuthPolicy {
        privilegedReads = Set.copyOf(privilegedReads);
    }
}
