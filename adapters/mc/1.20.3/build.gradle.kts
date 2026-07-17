plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.20.3"
    yarn = 1
    loader = "0.16.10"
    api = "0.91.1"
    authlib = "6.0.52"
}