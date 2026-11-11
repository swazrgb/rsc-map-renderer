package openrsc.bot.api;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record Messages(List<ServerMessage> last, List<ServerMessage> thisTick, int lastTick, int lastServerLineTick) {

    public static boolean fromAPlayer(ServerMessage line) {
        return line.sender() != null && !line.sender().isEmpty();
    }

    public static boolean breathedOn(List<String> thisTick) {
        for (String m : thisTick) {
            if (m != null && m.startsWith("The dragon breathes fire at you")) {
                return true;
            }
        }
        return false;
    }

    public List<String> thisTickText() {
        return texts(thisTick);
    }

    public List<String> lastText() {
        return texts(last);
    }

    private static List<String> texts(List<ServerMessage> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        return new AbstractList<>() {
            @Override
            public String get(int index) {
                return lines.get(index).text();
            }

            @Override
            public int size() {
                return lines.size();
            }
        };
    }

    public int ticksSince(int nowTick) {
        return lastTick == Integer.MIN_VALUE ? Integer.MAX_VALUE : nowTick - lastTick;
    }

    public int ticksSinceServerLine(int nowTick) {
        return lastServerLineTick == Integer.MIN_VALUE ? Integer.MAX_VALUE : nowTick - lastServerLineTick;
    }

    public List<String> thisTickServerText() {
        List<String> out = new ArrayList<>(thisTick.size());
        for (ServerMessage m : thisTick) {
            if (!fromAPlayer(m)) {
                out.add(m.text());
            }
        }
        return out;
    }

    public boolean saidRecently(String needle, int nowTick, int withinTicks) {
        if (ticksSince(nowTick) > withinTicks) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        for (ServerMessage m : last) {
            if (m.text().toLowerCase(Locale.ROOT).contains(n)) {
                return true;
            }
        }
        return false;
    }
}
