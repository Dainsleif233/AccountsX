plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("accountsx.modmenu.adapter")
}

adapter {
    minecraft = "1.21"
    yarn = 9
    loader = "0.16.10"
    api = "0.100.1"
    modmenu = "11.0.0"
}