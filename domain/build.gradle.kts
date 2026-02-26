plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(project(":resources"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
    testAnnotationProcessor(libs.netbeans.lookup)
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
    implementation(libs.xmpcore)
}
