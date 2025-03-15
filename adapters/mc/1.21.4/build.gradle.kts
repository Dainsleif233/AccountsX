plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.21.4"
    yarn = 8
    loader = "0.16.10"
    api = "0.118.5"
    authlib = "6.0.54"
}