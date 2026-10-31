package openrsc.nav.transports;

/** A transport whose engagement is entirely its {@link EngageSpec}. */
public interface DeclarativeTransport {

    /** The same value on every call: an engagement is fixed when the transport is built. */
    EngageSpec engageSpec();
}
