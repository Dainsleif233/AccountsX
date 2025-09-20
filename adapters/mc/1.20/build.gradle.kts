plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.20"
    yarn = 1
    loader = "0.16.10"
    api = "0.83.0"
    authlib = "4.0.43"
}