package openrsc.navweb.subst.openrsc.nav.engine;

import java.util.function.IntConsumer;

/**
 * Stands in for {@code openrsc.nav.engine.Parallel} in the TeaVM build ({@link
 * openrsc.navweb.NavWebSubstitution}): the module has one thread, so the loop runs in order on it.
 */
public final class Parallel {
    private Parallel() {}

    public static void forEach(int n, IntConsumer body) {
        for (int i = 0; i < n; i++) {
            body.accept(i);
        }
    }
}
