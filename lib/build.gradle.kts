plugins {
    id("jpt.java-conventions")
}

dependencies {
    // Lib has mostly JDK-only deps (Swing utilities, IO, etc.)
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
}
