package openrsc.navweb;

import org.teavm.extension.spi.substitution.SimpleSubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

/**
 * Tells TeaVM to compile {@code openrsc.navweb.subst.}<i>name</i> in place of two classes: slf4j's
 * {@code LoggerFactory}, which finds its provider at run time, and the engine's {@code Parallel},
 * whose parallel stream has no threads to use in the WebAssembly module. Registered as a service in
 * {@code META-INF/services}.
 */
public class NavWebSubstitution extends SimpleSubstitutionPolicy {

    @Override
    public void contribute(SubstitutionSink sink) {
        sink.selectClasses(named("org.slf4j.LoggerFactory").or(named("openrsc.nav.engine.Parallel")))
                .packagePrefix("openrsc.navweb.subst.");
    }
}
