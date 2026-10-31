package com.openrsc.client.entityhandling.defs.extras;

public class AnimationDef {
    public String name;
    public String category;
    private int charColour;
    private int blueMask;
    private boolean hasA;
    private boolean hasF;

    public AnimationDef(
            String name,
            String category,
            int charColour,
            int blueMask,
            int genderModel,
            boolean hasA,
            boolean hasF,
            int number) {
        this.name = name;
        this.category = category;
        this.charColour = charColour;
        this.blueMask = blueMask;
        this.hasA = hasA;
        this.hasF = hasF;
    }

    public AnimationDef(
            String name, String category, int charColour, int genderModel, boolean hasA, boolean hasF, int number) {
        this.name = name;
        this.category = category;
        this.charColour = charColour;
        this.blueMask = 0;
        this.hasA = hasA;
        this.hasF = hasF;
    }

    public int getCharColour() {
        return charColour;
    }

    public int getBlueMask() {
        return this.blueMask;
    }

    public boolean hasA() {
        return hasA;
    }

    public boolean hasF() {
        return hasF;
    }
}
