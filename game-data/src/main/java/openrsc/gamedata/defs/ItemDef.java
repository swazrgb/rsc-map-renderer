package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * An item, as the server's {@code ItemDefs.json} has it. {@code wearSlot} is the server's wield
 * position and {@code wearableId} its wearable type; a {@code femaleOnly} item cannot be worn by a
 * male character.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L669">Equipment.ableToEquip</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemDef(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("basePrice") int basePrice,
        @JsonProperty("isStackable") boolean stackable,
        @JsonProperty("isWearable") boolean wearable,
        @JsonProperty("requiredLevel") int requiredLevel,
        @JsonProperty("requiredSkillID") int requiredSkillId,
        @JsonProperty("wearSlot") int wearSlot,
        @JsonProperty("wearableID") int wearableId,
        @JsonProperty("isUntradable") boolean untradable,
        @JsonProperty("isNoteable") boolean noteable,
        @JsonProperty("isMembersOnly") boolean membersOnly,
        @JsonProperty("isFemaleOnly") boolean femaleOnly,
        @JsonProperty("command") String command,
        @JsonProperty("appearanceID") int appearanceId,
        @JsonProperty("armourBonus") int armourBonus,
        @JsonProperty("weaponAimBonus") int weaponAimBonus,
        @JsonProperty("weaponPowerBonus") int weaponPowerBonus,
        @JsonProperty("magicBonus") int magicBonus,
        @JsonProperty("prayerBonus") int prayerBonus) {

    /**
     * Whether a bank hands this item out as notes when asked: never a stackable, and an untradable
     * item only when it is flagged noteable.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/ItemDefinition.java#L470">ItemDefinition.isNoteable</a>
     */
    public boolean bankNoteable() {
        return !stackable && (!untradable || noteable);
    }

    /**
     * Whether this is a weapon-slot item other than a staff, bow or scythe: the test the server uses
     * for a weapon that cuts a web. A knife, which also cuts one, is not counted.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L30">CutWeb.onUseBound</a>
     */
    public boolean isMeleeWeapon() {
        if (wearSlot != 4 || name == null) {
            return false;
        }

        String n = name.toLowerCase(Locale.ROOT);
        return !n.contains("staff") && !n.contains("bow") && !n.contains("cythe");
    }
}
