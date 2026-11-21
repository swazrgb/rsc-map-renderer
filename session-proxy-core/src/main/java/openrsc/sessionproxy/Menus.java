package openrsc.sessionproxy;

import java.util.List;
import java.util.Locale;

/** Which option of a menu to answer, by keywords. */
final class Menus {

    private Menus() {}

    /**
     * The first option a keyword of {@code groups} names, -1 for none: an option equal to a keyword, else one that
     * starts with it as a word, else one that contains it, ignoring case; the groups in order at each.
     */
    static int optionFor(List<String> options, List<String[]> groups) {
        for (int pass = 0; pass < 3; pass++) {
            for (String[] group : groups) {
                for (String keyword : group) {
                    String k = keyword.toLowerCase(Locale.ROOT);
                    for (int i = 0; i < options.size(); i++) {
                        String option = options.get(i).toLowerCase(Locale.ROOT);
                        boolean hit = switch (pass) {
                            case 0 -> option.equals(k);
                            case 1 -> option.startsWith(k + " ");
                            default -> option.contains(k);
                        };
                        if (hit) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }
}
