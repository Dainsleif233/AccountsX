plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.21.4"
    yarn = 8
    loader = "0.19.3"
    api = "0.118.5"
    authlib = "6.0.54"
}
