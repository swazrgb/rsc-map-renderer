package com.openrsc.client.entityhandling.defs;

public abstract class EntityDef {
    public String name;
    public int id;

    EntityDef(String name, String description, int id) {
        this.name = name;
        this.id = id;
    }

    public String getName() {
        return name;
    }
}
