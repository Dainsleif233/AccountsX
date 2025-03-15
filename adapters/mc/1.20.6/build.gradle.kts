plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.20.6"
    yarn = 3
    loader = "0.16.10"
    api = "0.100.8"
    authlib = "6.0.54"
}