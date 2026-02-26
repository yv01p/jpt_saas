plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":jpt-api"))
    implementation(files("../Libraries/swingx-core.jar"))
}
