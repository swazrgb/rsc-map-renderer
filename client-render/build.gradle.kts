// A headless port of the OpenRSC software 3D renderer: terrain, scenery models and walls, into an
// int[] buffer.
plugins {
    id("openrsc.oss")
}

openrscJava {
    // Ported OpenRSC code, which names its constants its own way.
    errorproneArgs.add("-Xep:ConstantNaming:OFF")
}
