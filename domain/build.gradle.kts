plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
}
