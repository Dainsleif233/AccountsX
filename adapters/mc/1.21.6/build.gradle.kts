plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.21.6"
    yarn = 1
    loader = "0.16.14"
    api = "0.128.0"
    authlib = "6.0.54"
}