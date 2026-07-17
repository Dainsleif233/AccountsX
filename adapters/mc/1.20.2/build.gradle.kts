plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.20.2"
    yarn = 4
    loader = "0.16.10"
    api = "0.91.6"
    authlib = "5.0.47"
}