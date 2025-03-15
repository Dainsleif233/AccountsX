plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.20.1"
    yarn = 10
    loader = "0.16.10"
    api = "0.92.3"
    authlib = "4.0.43"
}