plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.21.11"
    yarn = 1
    loader = "0.18.2"
    api = "0.139.4"
    authlib = "7.0.61"
}