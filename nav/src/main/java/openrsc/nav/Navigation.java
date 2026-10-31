package openrsc.nav;

import openrsc.nav.engine.Engine;

public final class Navigation {

    private Navigation() {}

    /** Builds the planner over {@code inputs}, reading them once; it then serves every account on that world. */
    public static Planner load(NavInputs inputs) {
        return new Engine(inputs);
    }
}
