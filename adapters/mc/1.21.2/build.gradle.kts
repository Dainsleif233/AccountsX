plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.mc.adapter")
}

adapter {
    minecraft = "1.21.2"
    yarn = 1
    loader = "0.16.10"
    api = "0.106.1"
    authlib = "6.0.54"
}