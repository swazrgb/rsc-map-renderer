package openrsc.navweb.subst.org.slf4j;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Stands in for slf4j's {@code LoggerFactory} in the TeaVM build ({@link
 * openrsc.navweb.NavWebSubstitution}), so the module needs no slf4j provider. Every logger prints
 * INFO and above to standard output, which the browser shows on its console.
 */
public final class LoggerFactory {
    private LoggerFactory() {}

    public static Logger getLogger(Class<?> type) {
        return getLogger(type.getName());
    }

    public static Logger getLogger(String name) {
        return new ConsoleLogger(name);
    }

    private static final class ConsoleLogger extends LegacyAbstractLogger {
        ConsoleLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level, Marker marker, String pattern, Object[] args, Throwable t) {
            String msg = MessageFormatter.basicArrayFormat(pattern, args);
            String shortName = name.substring(name.lastIndexOf('.') + 1);
            System.out.println(level + " " + shortName + " - " + msg);
            if (t != null) {
                System.out.println("  " + t);
            }
        }
    }
}
